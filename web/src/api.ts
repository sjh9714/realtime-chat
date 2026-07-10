import { z, type ZodType } from 'zod';
import {
  apiErrorBodySchema,
  authResponseSchema,
  messagePageSchema,
  messageSyncSchema,
  roomDetailSchema,
  roomSchema,
  userSummarySchema,
} from './schemas';

export const API_URL = import.meta.env.VITE_API_URL ?? 'http://localhost:8080';
export const WS_URL = import.meta.env.VITE_WS_URL ?? 'ws://localhost:8080/ws';
export const DEMO_MODE = import.meta.env.VITE_DEMO_MODE === 'true';

let unauthorizedHandler: (() => void) | null = null;

export function registerUnauthorizedHandler(handler: () => void) {
  unauthorizedHandler = handler;
}

export class ApiError extends Error {
  constructor(
    public readonly status: number,
    message: string,
  ) {
    super(message);
  }
}

async function request<T>(
  path: string,
  schema: ZodType<T>,
  options: RequestInit = {},
  token?: string,
): Promise<T> {
  const headers = new Headers(options.headers);
  if (options.body) {
    headers.set('Content-Type', 'application/json');
  }
  if (token) {
    headers.set('Authorization', `Bearer ${token}`);
  }

  const response = await fetch(`${API_URL}${path}`, { ...options, headers });
  if (!response.ok) {
    if (response.status === 401) unauthorizedHandler?.();
    const rawBody: unknown = await response.json().catch(() => ({}));
    const body = apiErrorBodySchema.safeParse(rawBody);
    throw new ApiError(
      response.status,
      body.success ? body.data.message ?? '요청을 처리하지 못했습니다.' : '요청을 처리하지 못했습니다.',
    );
  }
  const rawBody: unknown = await response.json();
  return schema.parse(rawBody);
}

export const api = {
  login(email: string, password: string) {
    return request('/api/auth/login', authResponseSchema, {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    });
  },
  signup(email: string, password: string, nickname: string) {
    return request('/api/auth/signup', authResponseSchema, {
      method: 'POST',
      body: JSON.stringify({ email, password, nickname }),
    });
  },
  me(token: string) {
    return request('/api/users/me', userSummarySchema, {}, token);
  },
  searchUsers(token: string, nickname: string) {
    return request(
      `/api/users/search?nickname=${encodeURIComponent(nickname)}`,
      z.array(userSummarySchema),
      {},
      token,
    );
  },
  rooms(token: string) {
    return request('/api/rooms', z.array(roomSchema), {}, token);
  },
  room(token: string, roomId: number) {
    return request(`/api/rooms/${roomId}`, roomDetailSchema, {}, token);
  },
  createDirectRoom(token: string, targetUserId: number) {
    return request(
      '/api/rooms/direct',
      roomDetailSchema,
      { method: 'POST', body: JSON.stringify({ targetUserId }) },
      token,
    );
  },
  messages(token: string, roomId: number) {
    return request(`/api/rooms/${roomId}/messages?size=50`, messagePageSchema, {}, token);
  },
  syncMessages(token: string, roomId: number, afterMessageId?: number) {
    const query = afterMessageId ? `?afterMessageId=${afterMessageId}&limit=100` : '?limit=100';
    return request(`/api/rooms/${roomId}/messages/sync${query}`, messageSyncSchema, {}, token);
  },
  onlineMembers(token: string, roomId: number) {
    return request(
      `/api/rooms/${roomId}/members/online`,
      z.array(z.number().int().positive()),
      {},
      token,
    );
  },
  markRead(token: string, roomId: number, lastReadMessageId: number) {
    return fetch(`${API_URL}/api/rooms/${roomId}/read`, {
      method: 'POST',
      headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ lastReadMessageId }),
    }).then((response) => {
      if (response.status === 401) unauthorizedHandler?.();
      if (!response.ok) throw new ApiError(response.status, '읽음 상태를 저장하지 못했습니다.');
    });
  },
};
