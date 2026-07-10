import { useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api';
import type { Room } from '../types';

interface RoomSidebarProps {
  token: string;
  rooms: Room[];
  selectedRoomId: number | null;
  onSelectRoom: (roomId: number) => void;
  onCloseMobile: () => void;
}

function roomTime(room: Room) {
  const value = room.lastMessageAt ?? room.createdAt;
  return new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

export function RoomSidebar({
  token,
  rooms,
  selectedRoomId,
  onSelectRoom,
  onCloseMobile,
}: RoomSidebarProps) {
  const [nickname, setNickname] = useState('');
  const queryClient = useQueryClient();
  const search = useQuery({
    queryKey: ['users', 'search', nickname],
    queryFn: () => api.searchUsers(token, nickname),
    enabled: nickname.trim().length >= 2,
    staleTime: 10_000,
  });
  const createRoom = useMutation({
    mutationFn: (targetUserId: number) => api.createDirectRoom(token, targetUserId),
    onSuccess: async (room) => {
      await queryClient.invalidateQueries({ queryKey: ['rooms'] });
      onSelectRoom(room.id);
      setNickname('');
      onCloseMobile();
    },
  });

  return (
    <aside className="room-sidebar" aria-label="대화 목록">
      <header className="sidebar-header">
        <div>
          <p className="wordmark wordmark-small">Relay</p>
          <p className="sidebar-caption">저장 경계를 확인하는 채팅</p>
        </div>
        <button className="mobile-close" type="button" onClick={onCloseMobile} aria-label="대화 목록 닫기">
          닫기
        </button>
      </header>

      <section className="user-search" aria-labelledby="user-search-heading">
        <label id="user-search-heading" htmlFor="nickname-search">새 대화</label>
        <input
          id="nickname-search"
          type="search"
          placeholder="닉네임 두 글자 이상"
          value={nickname}
          onChange={(event) => setNickname(event.target.value)}
        />
        {nickname.trim().length >= 2 && (
          <div className="search-results" aria-live="polite">
            {search.isLoading && <p>사용자를 찾는 중…</p>}
            {search.data?.map((user) => (
              <button
                type="button"
                key={user.id}
                onClick={() => createRoom.mutate(user.id)}
                disabled={createRoom.isPending}
              >
                <span className="avatar" aria-hidden="true">{user.nickname.slice(0, 1)}</span>
                <span>{user.nickname}</span>
                <span aria-hidden="true">대화 시작</span>
              </button>
            ))}
            {search.data?.length === 0 && <p>일치하는 닉네임이 없습니다.</p>}
            {search.error instanceof Error && <p role="alert">{search.error.message}</p>}
          </div>
        )}
      </section>

      <nav className="room-list" aria-label="내 채팅방">
        <p className="room-list-label">대화 {rooms.length}</p>
        {rooms.length === 0 && <p className="empty-copy">닉네임을 검색해 첫 대화를 시작해 보세요.</p>}
        {rooms.map((room) => (
          <button
            type="button"
            key={room.id}
            aria-current={room.id === selectedRoomId ? 'page' : undefined}
            onClick={() => {
              onSelectRoom(room.id);
              onCloseMobile();
            }}
          >
            <span className="avatar" aria-hidden="true">{room.displayName.slice(0, 1)}</span>
            <span className="room-copy">
              <span className="room-title-row">
                <strong>{room.displayName}</strong>
                <time dateTime={room.lastMessageAt ?? room.createdAt}>{roomTime(room)}</time>
              </span>
              <span className="room-preview">
                {room.lastMessageContent ?? `${room.memberCount}명이 참여 중입니다.`}
              </span>
            </span>
            {room.unreadCount > 0 && (
              <span className="unread-count" aria-label={`읽지 않은 메시지 ${room.unreadCount}개`}>
                {room.unreadCount > 99 ? '99+' : room.unreadCount}
              </span>
            )}
          </button>
        ))}
      </nav>
    </aside>
  );
}
