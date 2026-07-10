import { describe, expect, it } from 'vitest';
import { chatMessageSchema, stompErrorSchema } from './schemas';

describe('STOMP runtime contracts', () => {
  const validPersistedFrame = {
    id: 42,
    messageKey: '8ad5a6a3-e90a-4f26-afb6-907720b1a5f0',
    clientMessageId: '8cccf981-d385-49f6-a5b4-fef715649da8',
    roomId: 7,
    senderId: 1,
    senderNickname: 'Alice',
    content: 'hello',
    type: 'TEXT',
    status: 'PERSISTED',
    createdAt: '2026-07-10T10:00:00Z',
  } as const;

  it.each([
    ['null DB id', { id: null }],
    ['null messageKey', { messageKey: null }],
    ['non-persisted status', { status: 'ACCEPTED' }],
  ])('rejects a server room frame with %s', (_label, invalidIdentity) => {
    const result = chatMessageSchema.safeParse({
      ...validPersistedFrame,
      ...invalidIdentity,
    });

    expect(result.success).toBe(false);
  });

  it('accepts a persisted room frame with both durable identities', () => {
    const result = chatMessageSchema.safeParse({
      ...validPersistedFrame,
      roomId: 7,
    });

    expect(result.success).toBe(true);
  });

  it('accepts a structured rate-limit correlation error', () => {
    const result = stompErrorSchema.safeParse({
      code: 'RATE_LIMITED',
      message: 'slow down',
      clientMessageId: '8cccf981-d385-49f6-a5b4-fef715649da8',
      roomId: 7,
      timestamp: '2026-07-10T10:00:00Z',
    });

    expect(result.success).toBe(true);
  });
});
