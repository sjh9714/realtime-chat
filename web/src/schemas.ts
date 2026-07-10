import { z } from 'zod';

const roomTypeSchema = z.enum(['DIRECT', 'GROUP']);
const messageTypeSchema = z.enum(['TEXT', 'IMAGE', 'FILE', 'SYSTEM']);

export const authResponseSchema = z.object({
  token: z.string().min(1),
  userId: z.number().int().positive(),
  email: z.string().email(),
  nickname: z.string().min(1),
});

export const userSummarySchema = z.object({
  id: z.number().int().positive(),
  nickname: z.string().min(1),
});

export const roomSchema = z.object({
  id: z.number().int().positive(),
  name: z.string().nullable(),
  displayName: z.string().min(1),
  type: roomTypeSchema,
  memberCount: z.number().int().nonnegative(),
  unreadCount: z.number().int().nonnegative(),
  lastMessageId: z.number().int().positive().nullable(),
  lastMessageContent: z.string().nullable(),
  lastMessageSenderNickname: z.string().nullable(),
  lastMessageAt: z.string().nullable(),
  createdAt: z.string(),
});

export const roomDetailSchema = z.object({
  id: z.number().int().positive(),
  name: z.string().nullable(),
  type: roomTypeSchema,
  createdBy: z.number().int().positive(),
  createdAt: z.string(),
  members: z.array(
    z.object({
      userId: z.number().int().positive(),
      nickname: z.string().min(1),
      unreadCount: z.number().int().nonnegative(),
    }),
  ),
});

export const chatMessageSchema = z.object({
  id: z.number().int().positive(),
  messageKey: z.string().uuid(),
  clientMessageId: z.string().uuid(),
  roomId: z.number().int().positive(),
  senderId: z.number().int().positive(),
  senderNickname: z.string().min(1),
  content: z.string(),
  type: messageTypeSchema,
  status: z.literal('PERSISTED'),
  createdAt: z.string(),
  failureReason: z.string().optional(),
});

export const messagePageSchema = z.object({
  messages: z.array(chatMessageSchema),
  hasMore: z.boolean(),
  nextCursor: z.number().int().positive().nullable(),
});

export const messageSyncSchema = z.object({
  messages: z.array(chatMessageSchema),
  hasMore: z.boolean(),
  lastMessageId: z.number().int().positive().nullable(),
});

export const publishAckSchema = z.object({
  clientMessageId: z.string().uuid(),
  roomId: z.number().int().positive(),
  status: z.literal('ACCEPTED'),
  acceptedAt: z.string(),
});

export const persistedAckSchema = z.object({
  clientMessageId: z.string().uuid(),
  messageKey: z.string().uuid(),
  messageId: z.number().int().positive(),
  roomId: z.number().int().positive(),
  status: z.literal('PERSISTED'),
  persistedAt: z.string(),
});

export const publishErrorSchema = z.object({
  clientMessageId: z.string().uuid(),
  roomId: z.number().int().positive(),
  status: z.literal('FAILED'),
  code: z.string().min(1),
  reason: z.string().min(1),
  failedAt: z.string(),
});

export const stompErrorSchema = z.object({
  code: z.string().min(1),
  message: z.string().min(1),
  clientMessageId: z.string().uuid().nullable(),
  roomId: z.number().int().positive().nullable(),
  timestamp: z.string(),
});

export const presenceEventSchema = z.object({
  userId: z.number().int().positive(),
  status: z.enum(['ONLINE', 'OFFLINE']),
  timestamp: z.number().int().nonnegative(),
});

export const apiErrorBodySchema = z.object({
  status: z.number().optional(),
  message: z.string().optional(),
});
