import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { api } from '../api';
import { useChatStore } from '../stores/chat-store';
import type { ChatMessage, ConnectionStatus, Room, UserSummary } from '../types';
import { DeliveryBadge } from './DeliveryBadge';

interface ConversationProps {
  token: string;
  currentUser: UserSummary;
  room: Room;
  connectionStatus: ConnectionStatus;
  sendMessage: (options: { content: string; clientMessageId?: string }) => void;
  onOpenRooms: () => void;
}

const EMPTY_MESSAGES: ChatMessage[] = [];
const EMPTY_USER_IDS: number[] = [];

function formatMessageTime(value: string) {
  return new Intl.DateTimeFormat('ko-KR', {
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(value));
}

export function Conversation({
  token,
  currentUser,
  room,
  connectionStatus,
  sendMessage,
  onOpenRooms,
}: ConversationProps) {
  const [content, setContent] = useState('');
  const timelineRef = useRef<HTMLDivElement>(null);
  const messages = useChatStore((state) => state.messagesByRoom[room.id] ?? EMPTY_MESSAGES);
  const onlineIds = useChatStore((state) => state.onlineByRoom[room.id] ?? EMPTY_USER_IDS);
  const detail = useQuery({
    queryKey: ['room', room.id],
    queryFn: () => api.room(token, room.id),
  });
  const markRead = useMutation({
    mutationFn: (messageId: number) => api.markRead(token, room.id, messageId),
  });
  const lastPersisted = [...messages].reverse().find((message) => message.id !== null);

  useEffect(() => {
    timelineRef.current?.scrollTo({
      top: timelineRef.current.scrollHeight,
      behavior: 'smooth',
    });
  }, [messages.length, room.id]);

  useEffect(() => {
    if (lastPersisted?.id) markRead.mutate(lastPersisted.id);
  }, [lastPersisted?.id, room.id]);

  function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const trimmed = content.trim();
    if (!trimmed) return;
    sendMessage({ content: trimmed });
    setContent('');
  }

  function retry(message: ChatMessage) {
    sendMessage({ content: message.content, clientMessageId: message.clientMessageId });
  }

  return (
    <main className="conversation" id="main-content">
      <header className="conversation-header">
        <button className="mobile-room-trigger" type="button" onClick={onOpenRooms} aria-label="대화 목록 열기">
          대화
        </button>
        <div>
          <h1>{room.displayName}</h1>
          <p>
            <span className={`connection-dot connection-${connectionStatus.toLowerCase()}`} />
            {connectionStatus === 'ONLINE'
              ? `${onlineIds.length}명 온라인`
              : connectionStatus === 'CONNECTING'
                ? '연결 중'
                : '오프라인 · 재연결 중'}
          </p>
        </div>
        <p className="member-summary">
          {detail.data?.members.map((member) => member.nickname).join(', ') ?? '멤버 확인 중'}
        </p>
      </header>

      <div
        className="message-timeline"
        ref={timelineRef}
        role="log"
        aria-label="메시지 타임라인"
        aria-live="polite"
        aria-relevant="additions text"
        tabIndex={0}
      >
        {messages.length === 0 && (
          <div className="conversation-empty">
            <p className="eyebrow">Conversation ready</p>
            <h2>첫 메시지를 보내 보세요.</h2>
            <p>큐 접수와 DB 저장 완료가 서로 다른 상태로 표시됩니다.</p>
          </div>
        )}
        {messages.map((message) => {
          const mine = message.senderId === currentUser.id;
          return (
            <article
              className={`message-row ${mine ? 'message-mine' : ''}`}
              key={message.id ?? message.clientMessageId}
              data-status={message.status}
            >
              {!mine && <span className="message-avatar" aria-hidden="true">{message.senderNickname.slice(0, 1)}</span>}
              <div className="message-block">
                {!mine && <p className="message-sender">{message.senderNickname}</p>}
                <div className="message-bubble">
                  <p>{message.content}</p>
                </div>
                <div className="message-meta">
                  <time dateTime={message.createdAt}>{formatMessageTime(message.createdAt)}</time>
                  {mine && <DeliveryBadge status={message.status} />}
                  {message.status === 'FAILED' && (
                    <button type="button" onClick={() => retry(message)}>다시 보내기</button>
                  )}
                </div>
                {message.failureReason && <p className="message-error" role="alert">{message.failureReason}</p>}
              </div>
            </article>
          );
        })}
      </div>

      <form className="composer" onSubmit={submit}>
        <label className="sr-only" htmlFor="message-content">메시지</label>
        <textarea
          id="message-content"
          value={content}
          onChange={(event) => setContent(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault();
              event.currentTarget.form?.requestSubmit();
            }
          }}
          placeholder={connectionStatus === 'ONLINE' ? '메시지를 입력하세요' : '연결이 복구될 때까지 기다려 주세요'}
          maxLength={2000}
          rows={1}
          disabled={connectionStatus !== 'ONLINE'}
        />
        <div className="composer-actions">
          <span>{content.length}/2,000</span>
          <button type="submit" disabled={connectionStatus !== 'ONLINE' || !content.trim()}>
            보내기
          </button>
        </div>
      </form>
    </main>
  );
}
