import { afterEach, describe, expect, it, vi } from 'vitest';
import { api, registerUnauthorizedHandler } from './api';

afterEach(() => {
  vi.unstubAllGlobals();
  registerUnauthorizedHandler(() => undefined);
});

describe('API runtime contracts', () => {
  it('rejects a successful HTTP response that violates the Zod contract', async () => {
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(
          JSON.stringify({ token: 'token', userId: 'not-a-number', email: 'a@b.com', nickname: 'a' }),
          { status: 200, headers: { 'Content-Type': 'application/json' } },
        ),
      ),
    );

    await expect(api.login('a@b.com', 'password123')).rejects.toThrow();
  });

  it('clears the central session boundary on every 401 response', async () => {
    const onUnauthorized = vi.fn();
    registerUnauthorizedHandler(onUnauthorized);
    vi.stubGlobal(
      'fetch',
      vi.fn().mockResolvedValue(
        new Response(JSON.stringify({ status: 401, message: 'expired' }), {
          status: 401,
          headers: { 'Content-Type': 'application/json' },
        }),
      ),
    );

    await expect(api.me('expired-token')).rejects.toThrow('expired');
    expect(onUnauthorized).toHaveBeenCalledOnce();
  });
});
