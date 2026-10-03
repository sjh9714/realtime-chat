import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api';
import { useAuthStore } from '../stores/auth-store';
import { useChatStore } from '../stores/chat-store';
import { useChatSocket } from '../hooks/use-chat-socket';
import { Conversation } from './Conversation';
import { CommandPalette } from './CommandPalette';
import { RoomSidebar } from './RoomSidebar';
import { takePendingInvite } from '../lib/invite';

export function ChatShell() {
  const [mobileRoomsOpen, setMobileRoomsOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);

  // ⌘K / Ctrl+K. 입력창에 있을 때도 열려야 한다. 방을 바꾸려고 손을 떼는 일이 없어야 하는 게
  // 이 기능의 요점이다.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === 'k') {
        e.preventDefault();
        setPaletteOpen((open) => !open);
      }
    };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, []);
  const queryClient = useQueryClient();
  const session = useAuthStore((state) => state.session)!;
  const logout = useAuthStore((state) => state.logout);
  const selectedRoomId = useChatStore((state) => state.selectedRoomId);
  const selectRoom = useChatStore((state) => state.selectRoom);
  const connectionStatus = useChatStore((state) => state.connectionStatus);
  const connectionNotice = useChatStore((state) => state.connectionNotice);
  const clearChat = useChatStore((state) => state.clear);
  const me = useQuery({
    queryKey: ['me', session.userId],
    queryFn: () => api.me(session.token),
  });
  const rooms = useQuery({
    queryKey: ['rooms', session.userId],
    queryFn: () => api.rooms(session.token),
    refetchInterval: 30_000,
  });

  useEffect(() => {
    if (selectedRoomId === null && rooms.data?.length) selectRoom(rooms.data[0].id);
  }, [rooms.data, selectRoom, selectedRoomId]);

  /*
   * 초대 링크(`?join=12`)를 타고 들어온 경우.
   *
   * 이미 참여 중이면 서버가 409를 주는데 그건 실패가 아니다. 방을 열어 주면 된다.
   * 어느 쪽이든 목록을 다시 받아야 새 방이 사이드바에 뜬다.
   */
  const [inviteError, setInviteError] = useState<string | null>(null);
  useEffect(() => {
    const roomId = takePendingInvite();
    if (roomId === null) return;
    let cancelled = false;
    void api
      .joinRoom(session.token, roomId)
      .catch((error: unknown) => {
        const status = (error as { status?: number }).status;
        if (status === 409) return;
        throw error;
      })
      .then(async () => {
        if (cancelled) return;
        await queryClient.invalidateQueries({ queryKey: ['rooms', session.userId] });
        if (!cancelled) selectRoom(roomId);
      })
      .catch((error: unknown) => {
        if (cancelled) return;
        setInviteError(
          error instanceof Error ? error.message : '초대받은 대화에 참여하지 못했습니다.',
        );
      });
    return () => {
      cancelled = true;
    };
  }, [queryClient, selectRoom, session.token, session.userId]);

  if (me.isLoading) {
    return <main className="loading-screen" id="main-content"><p>채팅 작업 공간을 여는 중…</p></main>;
  }
  if (me.error || !me.data) {
    return (
      <main className="loading-screen" id="main-content">
        <p role="alert">세션을 확인하지 못했습니다.</p>
        <button type="button" onClick={logout}>다시 로그인</button>
      </main>
    );
  }

  return (
    <>
      <CommandPalette
        open={paletteOpen}
        rooms={rooms.data ?? []}
        onSelectRoom={selectRoom}
        onClose={() => setPaletteOpen(false)}
      />
      <ConnectedChatShell
      token={session.token}
      currentUser={me.data}
      rooms={rooms.data ?? []}
      roomsError={rooms.error instanceof Error ? rooms.error.message : null}
      inviteError={inviteError}
      selectedRoomId={selectedRoomId}
      selectRoom={selectRoom}
      connectionStatus={connectionStatus}
      connectionNotice={connectionNotice}
      mobileRoomsOpen={mobileRoomsOpen}
      setMobileRoomsOpen={setMobileRoomsOpen}
        onLogout={() => {
          clearChat();
          queryClient.clear();
          logout();
        }}
      />
    </>
  );
}

interface ConnectedChatShellProps {
  token: string;
  currentUser: { id: number; nickname: string };
  rooms: Awaited<ReturnType<typeof api.rooms>>;
  roomsError: string | null;
  inviteError: string | null;
  selectedRoomId: number | null;
  selectRoom: (roomId: number | null) => void;
  connectionStatus: ReturnType<typeof useChatStore.getState>['connectionStatus'];
  connectionNotice: string | null;
  mobileRoomsOpen: boolean;
  setMobileRoomsOpen: (open: boolean) => void;
  onLogout: () => void;
}

function ConnectedChatShell({
  token,
  currentUser,
  rooms,
  roomsError,
  inviteError,
  selectedRoomId,
  selectRoom,
  connectionStatus,
  connectionNotice,
  mobileRoomsOpen,
  setMobileRoomsOpen,
  onLogout,
}: ConnectedChatShellProps) {
  const { sendMessage } = useChatSocket({ token, currentUser, selectedRoomId });
  const selectedRoom = rooms.find((room) => room.id === selectedRoomId) ?? null;

  return (
    <div className="app-shell">
      <a className="skip-link" href="#main-content">본문으로 바로가기</a>
      <div className={`sidebar-layer ${mobileRoomsOpen ? 'sidebar-open' : ''}`}>
        <RoomSidebar
          token={token}
          rooms={rooms}
          selectedRoomId={selectedRoomId}
          onSelectRoom={selectRoom}
          onCloseMobile={() => setMobileRoomsOpen(false)}
        />
      </div>
      {mobileRoomsOpen && (
        <button className="sidebar-scrim" type="button" aria-label="대화 목록 닫기" onClick={() => setMobileRoomsOpen(false)} />
      )}
      <section className="workspace">
        <div className="utility-bar">
          {/*
            사용자 id를 보여주지 않는다. 메신저에서 내 계정 번호는 알 이유가 없는 값이다.
            여기 있던 'How it stays correct' 서랍(DB transaction·optimistic 전송 설명)도
            지웠다. 제품 안에 제품 설명서를 넣지 않는다.
          */}
          <p>
            <strong>{currentUser.nickname}</strong>
          </p>
          <button type="button" onClick={onLogout}>로그아웃</button>
        </div>
        {/*
          알림 자리는 비어 있어도 항상 둔다.

          .workspace는 `48px auto minmax(0,1fr)` 3행 그리드다. 알림을 조건부로 그리면
          알림이 없을 때 대화가 세 번째 행이 아니라 두 번째 행(auto)에 들어가 내용 높이로
          줄어든다. 720px 화면에서 571px에서 끝나고 149px가 비어 있었다.
          자리를 고정해 대화가 언제나 마지막 행을 차지하게 한다.
        */}
        <div className="notice-slot">
          {connectionNotice && (
            <p className="connection-notice" role="status">{connectionNotice}</p>
          )}
          {roomsError && <p className="connection-notice notice-error" role="alert">{roomsError}</p>}
          {inviteError && <p className="connection-notice notice-error" role="alert">{inviteError}</p>}
        </div>
        {selectedRoom ? (
          <Conversation
            token={token}
            currentUser={currentUser}
            room={selectedRoom}
            connectionStatus={connectionStatus}
            sendMessage={sendMessage}
            onOpenRooms={() => setMobileRoomsOpen(true)}
          />
        ) : (
          <main className="no-room" id="main-content">
            <button className="mobile-room-trigger" type="button" onClick={() => setMobileRoomsOpen(true)}>대화 목록</button>
            <p className="eyebrow">No conversation selected</p>
            <h1>대화를 선택하거나 새로 시작하세요.</h1>
            <p>닉네임만 검색되며 이메일은 다른 사용자에게 노출되지 않습니다.</p>
          </main>
        )}
      </section>
    </div>
  );
}
