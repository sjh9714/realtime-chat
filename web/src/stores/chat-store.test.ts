import { beforeEach, describe, expect, it } from 'vitest';
import { lastPersistedMessageId, useChatStore } from './chat-store';
import type { ChatMessage } from '../types';

function optimistic(clientMessageId = 'client-1'): ChatMessage {
  return {
    id: null,
    messageKey: null,
    clientMessageId,
    roomId: 7,
    senderId: 1,
    senderNickname: '민준',
    content: '안녕하세요',
    type: 'TEXT',
    status: 'SENDING',
    createdAt: '2026-07-10T10:00:00Z',
  };
}

describe('chat store delivery reconciliation', () => {
  beforeEach(() => {
    useChatStore.getState().clear();
  });

  it('moves one optimistic message through SENDING, ACCEPTED and PERSISTED', () => {
    const store = useChatStore.getState();
    store.addOptimisticMessage(optimistic());
    expect(useChatStore.getState().messagesByRoom[7]).toEqual([
      expect.objectContaining({
        clientMessageId: 'client-1',
        id: null,
        status: 'SENDING',
      }),
    ]);

    store.applyAccepted({
      clientMessageId: 'client-1',
      roomId: 7,
      status: 'ACCEPTED',
      acceptedAt: '2026-07-10T10:00:01Z',
    });
    expect(useChatStore.getState().messagesByRoom[7]).toEqual([
      expect.objectContaining({
        clientMessageId: 'client-1',
        id: null,
        status: 'ACCEPTED',
      }),
    ]);

    store.applyPersistedAck({
      clientMessageId: 'client-1',
      messageKey: 'message-key-1',
      messageId: 42,
      roomId: 7,
      status: 'PERSISTED',
      persistedAt: '2026-07-10T10:00:02Z',
    });

    expect(useChatStore.getState().messagesByRoom[7]).toEqual([
      expect.objectContaining({
        clientMessageId: 'client-1',
        id: 42,
        messageKey: 'message-key-1',
        status: 'PERSISTED',
      }),
    ]);
  });

  it('deduplicates a rebroadcast by DB id and clientMessageId', () => {
    const store = useChatStore.getState();
    store.addOptimisticMessage(optimistic());
    const persisted: ChatMessage = {
      ...optimistic(),
      id: 42,
      messageKey: 'message-key-1',
      status: 'PERSISTED',
      createdAt: '2026-07-10T10:00:02Z',
    };

    store.applyPersistedMessage(persisted);
    store.applyPersistedMessage(persisted);

    expect(useChatStore.getState().messagesByRoom[7]).toHaveLength(1);
    expect(lastPersistedMessageId(useChatStore.getState().messagesByRoom[7])).toBe(42);
  });

  it('keeps a failed message retryable with the same correlation id', () => {
    const store = useChatStore.getState();
    store.addOptimisticMessage(optimistic());
    store.applyFailed({
      clientMessageId: 'client-1',
      roomId: 7,
      status: 'FAILED',
      code: 'SOCKET_OFFLINE',
      reason: '연결이 끊겼습니다.',
      failedAt: '2026-07-10T10:00:01Z',
    });
    store.addOptimisticMessage(optimistic());

    expect(useChatStore.getState().messagesByRoom[7]).toEqual([
      expect.objectContaining({
        clientMessageId: 'client-1',
        status: 'SENDING',
        failureReason: undefined,
      }),
    ]);
  });

  it('marks the matching optimistic message FAILED for a structured rate-limit error', () => {
    const store = useChatStore.getState();
    store.addOptimisticMessage(optimistic('rate-limited-client'));

    store.applyFailed({
      clientMessageId: 'rate-limited-client',
      roomId: 7,
      status: 'FAILED',
      code: 'RATE_LIMITED',
      reason: '잠시 후 다시 시도해 주세요.',
      failedAt: '2026-07-10T10:00:01Z',
    });

    expect(useChatStore.getState().messagesByRoom[7]).toEqual([
      expect.objectContaining({
        clientMessageId: 'rate-limited-client',
        status: 'FAILED',
        failureReason: '잠시 후 다시 시도해 주세요.',
      }),
    ]);
  });
});
