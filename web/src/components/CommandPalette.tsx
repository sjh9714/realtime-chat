import { useEffect, useMemo, useRef, useState } from 'react';
import type { Room } from '../types';

interface CommandPaletteProps {
  open: boolean;
  rooms: Room[];
  onSelectRoom: (roomId: number) => void;
  onClose: () => void;
}

/**
 * 방 이동을 키보드로.
 *
 * 채팅은 손가락이 홈 포지션에 있을 때 일어나는 일이다. 방을 바꾸려고 마우스로 옮겨 갔다
 * 돌아오는 왕복이 대화의 흐름을 끊는다. Superhuman·Linear가 푼 방식이 이것이다(DESIGN.md).
 *
 * **단축키는 빠른 길이지 유일한 길이 아니다.** 사이드바 클릭은 그대로 되고,
 * 이 팔레트 자체도 마우스로 전부 조작된다.
 */
export function CommandPalette({ open, rooms, onSelectRoom, onClose }: CommandPaletteProps) {
  const [query, setQuery] = useState('');
  const [active, setActive] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);
  const dialogRef = useRef<HTMLDivElement>(null);
  // 닫을 때 포커스를 원래 자리로 되돌린다. 안 그러면 body로 떨어져 탭 순서가 처음부터다.
  const returnFocus = useRef<HTMLElement | null>(null);

  const matches = useMemo(() => {
    const q = query.trim().toLowerCase();
    if (!q) return rooms;
    return rooms.filter((r) => r.displayName.toLowerCase().includes(q));
  }, [query, rooms]);

  useEffect(() => {
    if (!open) return;
    returnFocus.current = document.activeElement as HTMLElement | null;
    setQuery('');
    setActive(0);
    inputRef.current?.focus();
    return () => returnFocus.current?.focus();
  }, [open]);

  // 목록이 줄면 선택 위치가 밖으로 나갈 수 있다
  useEffect(() => {
    setActive((current) => Math.min(current, Math.max(matches.length - 1, 0)));
  }, [matches.length]);

  if (!open) return null;

  const choose = (roomId: number) => {
    onSelectRoom(roomId);
    onClose();
  };

  const onKeyDown = (event: React.KeyboardEvent) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      onClose();
      return;
    }
    if (event.key === 'ArrowDown') {
      event.preventDefault();
      setActive((i) => (matches.length ? (i + 1) % matches.length : 0));
      return;
    }
    if (event.key === 'ArrowUp') {
      event.preventDefault();
      setActive((i) => (matches.length ? (i - 1 + matches.length) % matches.length : 0));
      return;
    }
    if (event.key === 'Enter' && matches[active]) {
      event.preventDefault();
      choose(matches[active].id);
      return;
    }
    // 포커스가 팔레트 밖으로 새지 않게 가둔다. 뒤의 화면은 지금 조작 대상이 아니다.
    if (event.key === 'Tab') {
      const focusable = dialogRef.current?.querySelectorAll<HTMLElement>(
        'input, button, [tabindex]:not([tabindex="-1"])',
      );
      if (!focusable || focusable.length === 0) return;
      const first = focusable[0];
      const last = focusable[focusable.length - 1];
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault();
        last.focus();
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    }
  };

  return (
    <div className="palette-backdrop" onMouseDown={onClose}>
      <div
        ref={dialogRef}
        className="palette"
        role="dialog"
        aria-modal="true"
        aria-label="명령 팔레트"
        onMouseDown={(e) => e.stopPropagation()}
        onKeyDown={onKeyDown}
      >
        <input
          ref={inputRef}
          className="palette-input"
          type="text"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="대화 검색"
          aria-label="대화 검색"
          aria-controls="palette-results"
          autoComplete="off"
        />
        <ul id="palette-results" className="palette-results" role="listbox" aria-label="대화 목록">
          {matches.length === 0 && <li className="palette-empty">일치하는 대화가 없습니다</li>}
          {matches.map((room, i) => (
            <li key={room.id}>
              <button
                type="button"
                role="option"
                aria-selected={i === active}
                className={`palette-item${i === active ? ' is-active' : ''}`}
                onMouseEnter={() => setActive(i)}
                onClick={() => choose(room.id)}
              >
                <span>{room.displayName}</span>
                {room.unreadCount > 0 && <em>{room.unreadCount}</em>}
              </button>
            </li>
          ))}
        </ul>
        <p className="palette-hint">
          <kbd>↑</kbd> <kbd>↓</kbd> 이동 · <kbd>Enter</kbd> 열기 · <kbd>Esc</kbd> 닫기
        </p>
      </div>
    </div>
  );
}
