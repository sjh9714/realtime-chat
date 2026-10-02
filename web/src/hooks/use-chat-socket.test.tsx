import { act, renderHook } from '@testing-library/react';
import { beforeEach, expect, it, vi } from 'vitest';
import { api } from '../api';
import { useChatStore } from '../stores/chat-store';
import { useChatSocket } from './use-chat-socket';

vi.mock('@stomp/stompjs', () => ({ Client: class {
  connected = false;
  activate() {}
  async deactivate() {}
} }));
const queryClient = { invalidateQueries: vi.fn() };
vi.mock('@tanstack/react-query', () => ({ useQueryClient: () => queryClient }));
vi.mock('../api', () => ({ WS_URL: 'ws://localhost/ws', api: {
  messages: vi.fn(), syncMessages: vi.fn(), onlineMembers: vi.fn(),
} }));
function message(id: number): Awaited<ReturnType<typeof api.messages>>['messages'][number] {
  return { id, messageKey: `key-${id}`, clientMessageId: `client-${id}`, roomId: 7,
    senderId: 2, senderNickname: '상대', senderBot: false, content: `메시지 ${id}`, type: 'TEXT',
    status: 'PERSISTED', createdAt: '2026-10-02T00:00:00Z' };
}
const user = { id: 1, nickname: '나', email: 'me@example.com' };
function setup() {
  return renderHook(() => useChatSocket({ token: 'test', currentUser: user, selectedRoomId: null }));
}
beforeEach(() => {
  vi.resetAllMocks();
  useChatStore.getState().clear();
  vi.mocked(api.messages).mockResolvedValue({ messages: [message(10)], hasMore: false, nextCursor: null });
  vi.mocked(api.onlineMembers).mockResolvedValue([]);
});

it('10까지 이력 조회 후 11 누락·12 실시간 수신이어도 재접속은 11을 복구한다', async () => {
  const { result } = setup();
  await act(() => result.current.syncRoom(7));
  useChatStore.getState().applyPersistedMessage(message(12));
  vi.mocked(api.syncMessages).mockImplementation(async (_token, _room, after) => ({
    messages: [message(11), message(12)].filter(item => item.id! > (after ?? 0)),
    lastMessageId: 12, hasMore: false,
  }));
  await act(() => result.current.syncRoom(7));
  expect(api.syncMessages).toHaveBeenCalledWith('test', 7, 10);
  expect(useChatStore.getState().messagesByRoom[7].map(item => item.id)).toEqual([10, 11, 12]);
});

it('이력의 다음 페이지가 실패하면 완료 기준을 앞당기지 않고 다시 조회한다', async () => {
  const { result } = setup();
  await act(() => result.current.syncRoom(7));
  vi.mocked(api.syncMessages)
    .mockResolvedValueOnce({ messages: [message(11)], lastMessageId: 11, hasMore: true })
    .mockRejectedValueOnce(new Error('일시 오류'));
  await act(() => result.current.syncRoom(7));
  expect(useChatStore.getState().connectionNotice).toBe('일시 오류');
  vi.mocked(api.syncMessages).mockResolvedValue({ messages: [message(11), message(12)], lastMessageId: 12, hasMore: false });
  await act(() => result.current.syncRoom(7));
  expect(vi.mocked(api.syncMessages).mock.calls.at(-1)).toEqual(['test', 7, 10]);
  expect(useChatStore.getState().messagesByRoom[7].map(item => item.id)).toEqual([10, 11, 12]);
});

it('처음에 비어 있던 방도 이후에는 0부터 페이지를 따라 복구한다', async () => {
  vi.mocked(api.messages).mockResolvedValue({ messages: [], hasMore: false, nextCursor: null });
  const { result } = setup();
  await act(() => result.current.syncRoom(7));
  useChatStore.getState().applyPersistedMessage(message(12));
  vi.mocked(api.syncMessages).mockResolvedValue({ messages: [message(11), message(12)], lastMessageId: 12, hasMore: false });
  await act(() => result.current.syncRoom(7));
  expect(api.syncMessages).toHaveBeenCalledWith('test', 7, 0);
  expect(useChatStore.getState().messagesByRoom[7].map(item => item.id)).toEqual([11, 12]);
});
