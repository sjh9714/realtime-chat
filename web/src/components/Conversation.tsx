import { useEffect, useRef, useState, type FormEvent } from 'react';
import { useMutation, useQuery } from '@tanstack/react-query';
import { api } from '../api';
import { useChatStore } from '../stores/chat-store';
import type { ChatMessage, ConnectionStatus, Room, UserSummary } from '../types';
import { DeliveryBadge } from './DeliveryBadge';
import { inviteUrl } from '../lib/invite';

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

function formatDateDivider(value: string) {
  const date = new Date(value);
  const today = new Date();
  const days = Math.round(
    (new Date(today.getFullYear(), today.getMonth(), today.getDate()).getTime() -
      new Date(date.getFullYear(), date.getMonth(), date.getDate()).getTime()) /
      86_400_000,
  );
  if (days === 0) return '오늘';
  if (days === 1) return '어제';
  return new Intl.DateTimeFormat('ko-KR', {
    year: date.getFullYear() === today.getFullYear() ? undefined : 'numeric',
    month: 'long',
    day: 'numeric',
    weekday: 'short',
  }).format(date);
}

function sameDay(a: string, b: string) {
  const x = new Date(a);
  const y = new Date(b);
  return (
    x.getFullYear() === y.getFullYear() && x.getMonth() === y.getMonth() && x.getDate() === y.getDate()
  );
}

/**
 * 연속 메시지를 묶는 간격.
 *
 * 메신저는 같은 사람이 이어 보낸 메시지에 이름과 아바타를 반복하지 않는다.
 * 5분은 Discord 실측이 아니라 통용값이다. 남의 계정을 뒤져 간격을 재는 대신
 * 흔히 쓰이는 값을 골랐다(`web/DESIGN.md`).
 */
const GROUP_WINDOW_MS = 5 * 60 * 1000;

/**
 * 화면에 그릴 항목으로 펼친다. 날짜가 바뀌면 그 앞에 구분선을 끼운다.
 *
 * `head`는 묶음의 첫 줄(이름·아바타를 그린다), `tail`은 묶음의 마지막 줄(시각을 그린다).
 * 하나짜리 묶음은 둘 다 참이다.
 */
type Entry =
  | { kind: 'divider'; key: string; label: string }
  | { kind: 'message'; key: string; message: ChatMessage; head: boolean; tail: boolean };

function buildEntries(messages: ChatMessage[]): Entry[] {
  const entries: Entry[] = [];
  messages.forEach((message, index) => {
    const prev = messages[index - 1];
    const next = messages[index + 1];
    const newDay = !prev || !sameDay(prev.createdAt, message.createdAt);
    if (newDay) {
      entries.push({
        kind: 'divider',
        key: `divider-${message.id ?? message.clientMessageId}`,
        label: formatDateDivider(message.createdAt),
      });
    }

    const runsWith = (other: ChatMessage | undefined) =>
      !!other &&
      other.senderId === message.senderId &&
      sameDay(other.createdAt, message.createdAt) &&
      Math.abs(new Date(other.createdAt).getTime() - new Date(message.createdAt).getTime()) <
        GROUP_WINDOW_MS;

    entries.push({
      kind: 'message',
      key: String(message.id ?? message.clientMessageId),
      message,
      head: newDay || !runsWith(prev),
      // 실패한 메시지는 다시 보내기 단추가 붙으므로 언제나 꼬리로 둔다
      tail: !runsWith(next) || message.status === 'FAILED',
    });
  });
  return entries;
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
  const [copied, setCopied] = useState(false);
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
        {/*
          초대 링크. 1:1 대화에는 없다. 서버가 DIRECT 방 참여를 거부한다.
          이게 있어야 이 앱이 "혼자 보는 화면"이 아니라 쓸 수 있는 메신저가 된다.
        */}
        {room.type === 'GROUP' && (
          <button
            className="invite-copy"
            type="button"
            onClick={async () => {
              await navigator.clipboard.writeText(inviteUrl(room.id));
              setCopied(true);
              window.setTimeout(() => setCopied(false), 2000);
            }}
          >
            {copied ? '복사했습니다' : '초대 링크 복사'}
          </button>
        )}
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
            {/* 큐와 DB 이야기를 여기서 하지 않는다. 빈 대화가 할 말은 하나다 */}
            <h2>첫 메시지를 보내 보세요.</h2>
          </div>
        )}
        {buildEntries(messages).map((entry) => {
          if (entry.kind === 'divider') {
            return (
              <p className="day-divider" key={entry.key}>
                <span>{entry.label}</span>
              </p>
            );
          }

          const { message, head, tail } = entry;
          const mine = message.senderId === currentUser.id;
          return (
            <article
              className={[
                'message-row',
                mine ? 'message-mine' : '',
                head ? 'is-head' : 'is-run',
                tail ? 'is-tail' : '',
              ].filter(Boolean).join(' ')}
              key={entry.key}
              data-status={message.status}
            >
              {/* 묶음의 첫 줄에만 아바타를 그린다. 이어지는 줄은 자리만 비워 나란히 선다 */}
              {!mine && (
                head
                  ? <span className="message-avatar" aria-hidden="true">{message.senderNickname.slice(0, 1)}</span>
                  : <span className="message-avatar message-avatar-blank" aria-hidden="true" />
              )}
              <div className="message-block">
                {!mine && head && (
                  <p className="message-sender">
                    {message.senderNickname}
                    {/* 봇을 사람인 척 두지 않는다. 색이 아니라 글자로 적는다 */}
                    {message.senderBot && <span className="bot-badge">BOT</span>}
                  </p>
                )}
                {/* 시각은 묶음의 마지막 줄에만, 말풍선 옆에 붙는다 */}
                <div className="message-line">
                  <div className="message-bubble">
                    <p>{message.content}</p>
                  </div>
                  {tail && (
                    <span className="message-meta">
                      {mine && <DeliveryBadge status={message.status} />}
                      <time dateTime={message.createdAt}>{formatMessageTime(message.createdAt)}</time>
                    </span>
                  )}
                </div>
                {message.status === 'FAILED' && (
                  <button className="message-retry" type="button" onClick={() => retry(message)}>다시 보내기</button>
                )}
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
          {/*
            글자 수는 한도에 가까워질 때만 보여준다. 메신저가 언제나 `0/2,000`을
            띄워 두지는 않는다. 평소에는 알 이유가 없는 값이다.
          */}
          {content.length > 1800 && (
            <span role="status">{content.length.toLocaleString('ko-KR')}/2,000</span>
          )}
          <button type="submit" disabled={connectionStatus !== 'ONLINE' || !content.trim()}>
            보내기
          </button>
        </div>
      </form>
    </main>
  );
}
