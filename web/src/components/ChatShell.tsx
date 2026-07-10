import { useEffect, useState } from 'react';
import { useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api';
import { useAuthStore } from '../stores/auth-store';
import { useChatStore } from '../stores/chat-store';
import { useChatSocket } from '../hooks/use-chat-socket';
import { Conversation } from './Conversation';
import { RoomSidebar } from './RoomSidebar';

export function ChatShell() {
  const [mobileRoomsOpen, setMobileRoomsOpen] = useState(false);
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
    <ConnectedChatShell
      token={session.token}
      currentUser={me.data}
      rooms={rooms.data ?? []}
      roomsError={rooms.error instanceof Error ? rooms.error.message : null}
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
  );
}

interface ConnectedChatShellProps {
  token: string;
  currentUser: { id: number; nickname: string };
  rooms: Awaited<ReturnType<typeof api.rooms>>;
  roomsError: string | null;
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
          <p>
            <strong>{currentUser.nickname}</strong>
            <span>#{currentUser.id}</span>
          </p>
          <details className="correctness-details">
            <summary>How it stays correct</summary>
            <div className="correctness-panel">
              <p className="eyebrow">Delivery contract</p>
              <h2>메시지가 사라지거나 두 번 보이지 않도록</h2>
              <ol>
                <li>
                  <strong>DB commit → broadcast</strong>
                  <span>메시지는 DB transaction이 commit된 뒤에만 다른 사용자에게 전달됩니다.</span>
                </li>
                <li>
                  <strong>두 경계에서 중복 제거</strong>
                  <span><code>clientMessageId</code>로 optimistic 전송과 retry를 맞추고, DB message ID로 sync와 broadcast 결과를 합칩니다.</span>
                </li>
                <li>
                  <strong>Cursor로 재연결</strong>
                  <span>마지막으로 본 DB message ID 다음부터 조회해 연결이 끊긴 동안 빠진 메시지만 합칩니다.</span>
                </li>
              </ol>
            </div>
          </details>
          <button type="button" onClick={onLogout}>로그아웃</button>
        </div>
        {connectionNotice && (
          <p className="connection-notice" role="status">{connectionNotice}</p>
        )}
        {roomsError && <p className="connection-notice notice-error" role="alert">{roomsError}</p>}
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
