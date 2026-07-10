import { create } from 'zustand';
import type {
  ChatMessage,
  ConnectionStatus,
  PersistedAck,
  PublishAck,
  PublishError,
} from '../types';

interface ChatState {
  selectedRoomId: number | null;
  connectionStatus: ConnectionStatus;
  messagesByRoom: Record<number, ChatMessage[]>;
  onlineByRoom: Record<number, number[]>;
  connectionNotice: string | null;
  selectRoom: (roomId: number | null) => void;
  setConnectionStatus: (status: ConnectionStatus) => void;
  setConnectionNotice: (notice: string | null) => void;
  setOnlineMembers: (roomId: number, userIds: number[]) => void;
  applyPresence: (roomId: number, userId: number, online: boolean) => void;
  replaceMessages: (roomId: number, messages: ChatMessage[]) => void;
  mergePersistedMessages: (roomId: number, messages: ChatMessage[]) => void;
  addOptimisticMessage: (message: ChatMessage) => void;
  applyAccepted: (ack: PublishAck) => void;
  applyPersistedAck: (ack: PersistedAck) => void;
  applyPersistedMessage: (message: ChatMessage) => void;
  applyFailed: (error: PublishError) => void;
  clear: () => void;
}

function mergeMessage(list: ChatMessage[], incoming: ChatMessage): ChatMessage[] {
  const existingIndex = list.findIndex(
    (message) =>
      (incoming.id !== null && message.id === incoming.id) ||
      message.clientMessageId === incoming.clientMessageId,
  );
  const next = [...list];
  if (existingIndex >= 0) {
    next[existingIndex] = { ...next[existingIndex], ...incoming, failureReason: undefined };
  } else {
    next.push(incoming);
  }
  return next.sort((left, right) => {
    if (left.id !== null && right.id !== null) return left.id - right.id;
    return new Date(left.createdAt).getTime() - new Date(right.createdAt).getTime();
  });
}

function updateByClientId(
  state: ChatState,
  roomId: number,
  clientMessageId: string,
  update: (message: ChatMessage) => ChatMessage,
): Partial<ChatState> {
  const messages = state.messagesByRoom[roomId] ?? [];
  return {
    messagesByRoom: {
      ...state.messagesByRoom,
      [roomId]: messages.map((message) =>
        message.clientMessageId === clientMessageId ? update(message) : message,
      ),
    },
  };
}

export const useChatStore = create<ChatState>((set) => ({
  selectedRoomId: null,
  connectionStatus: 'OFFLINE',
  messagesByRoom: {},
  onlineByRoom: {},
  connectionNotice: null,
  selectRoom: (selectedRoomId) => set({ selectedRoomId }),
  setConnectionStatus: (connectionStatus) => set({ connectionStatus }),
  setConnectionNotice: (connectionNotice) => set({ connectionNotice }),
  setOnlineMembers: (roomId, userIds) =>
    set((state) => ({ onlineByRoom: { ...state.onlineByRoom, [roomId]: userIds } })),
  applyPresence: (roomId, userId, online) =>
    set((state) => {
      const current = new Set(state.onlineByRoom[roomId] ?? []);
      if (online) current.add(userId);
      else current.delete(userId);
      return { onlineByRoom: { ...state.onlineByRoom, [roomId]: [...current] } };
    }),
  replaceMessages: (roomId, messages) =>
    set((state) => ({ messagesByRoom: { ...state.messagesByRoom, [roomId]: messages } })),
  mergePersistedMessages: (roomId, messages) =>
    set((state) => ({
      messagesByRoom: {
        ...state.messagesByRoom,
        [roomId]: messages.reduce(
          (current, message) => mergeMessage(current, message),
          state.messagesByRoom[roomId] ?? [],
        ),
      },
    })),
  addOptimisticMessage: (message) =>
    set((state) => ({
      messagesByRoom: {
        ...state.messagesByRoom,
        [message.roomId]: mergeMessage(state.messagesByRoom[message.roomId] ?? [], message),
      },
    })),
  applyAccepted: (ack) =>
    set((state) =>
      updateByClientId(state, ack.roomId, ack.clientMessageId, (message) => ({
        ...message,
        status: message.status === 'PERSISTED' ? 'PERSISTED' : 'ACCEPTED',
      })),
    ),
  applyPersistedAck: (ack) =>
    set((state) =>
      updateByClientId(state, ack.roomId, ack.clientMessageId, (message) => ({
        ...message,
        id: ack.messageId,
        messageKey: ack.messageKey,
        status: 'PERSISTED',
        createdAt: ack.persistedAt,
        failureReason: undefined,
      })),
    ),
  applyPersistedMessage: (message) =>
    set((state) => ({
      messagesByRoom: {
        ...state.messagesByRoom,
        [message.roomId]: mergeMessage(state.messagesByRoom[message.roomId] ?? [], message),
      },
    })),
  applyFailed: (error) =>
    set((state) =>
      updateByClientId(state, error.roomId, error.clientMessageId, (message) => ({
        ...message,
        status: 'FAILED',
        failureReason: error.reason,
      })),
    ),
  clear: () =>
    set({
      selectedRoomId: null,
      connectionStatus: 'OFFLINE',
      messagesByRoom: {},
      onlineByRoom: {},
      connectionNotice: null,
    }),
}));

export function lastPersistedMessageId(messages: ChatMessage[]): number | undefined {
  return messages.reduce<number | undefined>(
    (highest, message) =>
      message.status === 'PERSISTED' && message.id !== null
        ? Math.max(highest ?? 0, message.id)
        : highest,
    undefined,
  );
}
