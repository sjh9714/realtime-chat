import AxeBuilder from '@axe-core/playwright';
import { Client } from '@stomp/stompjs';
import { expect, test, type APIRequestContext, type Browser, type BrowserContext, type Page } from '@playwright/test';
import WebSocket from 'ws';

const API_URL = process.env.E2E_API_URL ?? 'http://127.0.0.1:18080';
const WS_URL = process.env.E2E_WS_URL ?? `${API_URL.replace(/^http/, 'ws')}/ws`;
const WS_ORIGIN = new URL(WS_URL).origin;
const ALICE_NODE_WS_URL = process.env.E2E_ALICE_WS_URL ?? `${WS_ORIGIN}/ws/app-1`;
const BOB_NODE_WS_URL = process.env.E2E_BOB_WS_URL ?? `${WS_ORIGIN}/ws/app-2`;

interface AuthSession {
  token: string;
  userId: number;
  email: string;
  nickname: string;
}

interface Diagnostics {
  pageErrors: string[];
  consoleErrors: string[];
}

interface StompConnection {
  client: Client;
  instanceId: string;
}

function monitor(page: Page): Diagnostics {
  const diagnostics: Diagnostics = { pageErrors: [], consoleErrors: [] };
  page.on('pageerror', (error) => diagnostics.pageErrors.push(error.message));
  page.on('console', (message) => {
    if (message.type() === 'error') diagnostics.consoleErrors.push(message.text());
  });
  return diagnostics;
}

async function signup(request: APIRequestContext, label: string): Promise<AuthSession> {
  const suffix = `${label}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
  const response = await request.post(`${API_URL}/api/auth/signup`, {
    data: {
      email: `${suffix}@example.com`,
      password: 'password123',
      nickname: `${label}${suffix.slice(-6)}`,
    },
  });
  expect(response.ok()).toBeTruthy();
  return (await response.json()) as AuthSession;
}

async function authenticatedPage(
  browser: Browser,
  session: AuthSession,
  expectedPeer?: string,
): Promise<{ context: BrowserContext; page: Page; diagnostics: Diagnostics }> {
  const context = await browser.newContext();
  await context.addInitScript((value: AuthSession) => {
    sessionStorage.setItem('relay-auth', JSON.stringify({ state: { session: value }, version: 0 }));
  }, session);
  const page = await context.newPage();
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const diagnostics = monitor(page);
  await page.goto('/');
  if (expectedPeer) {
    await expect(page.getByRole('heading', { name: expectedPeer })).toBeVisible();
  } else {
    await expect(
      page.getByRole('heading', { name: '대화를 선택하거나 새로 시작하세요.' }),
    ).toBeVisible();
  }
  return { context, page, diagnostics };
}

async function createDirectRoomThroughUi(page: Page, peerNickname: string): Promise<number> {
  await page.getByRole('searchbox', { name: '새 대화' }).fill(peerNickname);
  const peer = page.getByRole('button', { name: peerNickname, exact: true });
  await expect(peer).toBeVisible();

  const responsePromise = page.waitForResponse(
    (response) =>
      response.request().method() === 'POST' &&
      new URL(response.url()).pathname === '/api/rooms/direct',
  );
  await peer.click();
  const response = await responsePromise;
  expect(response.status()).toBe(201);
  const room = (await response.json()) as { id: number };
  await expect(page.getByRole('heading', { name: peerNickname })).toBeVisible();
  return room.id;
}

async function connectStomp(token: string, url: string): Promise<StompConnection> {
  return new Promise((resolve, reject) => {
    let upgradeInstanceId: string | undefined;
    const client = new Client({
      webSocketFactory: () => {
        const socket = new WebSocket(url);
        socket.once('upgrade', (response) => {
          const header = response.headers['x-app-instance'];
          upgradeInstanceId = Array.isArray(header) ? header[0] : header;
        });
        return socket as never;
      },
      connectHeaders: { Authorization: `Bearer ${token}` },
      reconnectDelay: 0,
      connectionTimeout: 8_000,
      onConnect: () => {
        if (!upgradeInstanceId) {
          reject(new Error(`WebSocket upgrade from ${url} omitted x-app-instance`));
          void client.deactivate();
          return;
        }
        resolve({ client, instanceId: upgradeInstanceId });
      },
      onStompError: (frame) => reject(new Error(frame.headers.message ?? frame.body)),
      onWebSocketError: () => reject(new Error('WebSocket connection failed')),
    });
    client.activate();
  });
}

function publish(client: Client, roomId: number, content: string, clientMessageId: string) {
  client.publish({
    destination: '/app/chat.send',
    body: JSON.stringify({ roomId, content, clientMessageId, type: 'TEXT' }),
  });
}

async function armFailure(request: APIRequestContext, stage: 'database' | 'redis') {
  const response = await request.post(`${API_URL}/api/demo/failures/${stage}`);
  expect(response.status()).toBe(204);
}

async function count(
  request: APIRequestContext,
  path: string,
): Promise<number> {
  const response = await request.get(`${API_URL}${path}`);
  expect(response.ok()).toBeTruthy();
  const body = (await response.json()) as { count: number };
  return body.count;
}

function messageArticle(page: Page, content: string) {
  return page.locator('#main-content article').filter({ hasText: content });
}

async function assertNoSeriousAxeViolations(page: Page) {
  const result = await new AxeBuilder({ page }).analyze();
  const violations = result.violations.filter(
    (violation) => violation.impact === 'serious' || violation.impact === 'critical',
  );
  expect(violations).toEqual([]);
}

function unexpectedConsoleErrors(errors: string[]) {
  return errors.filter(
    (message) =>
      !message.includes('ERR_INTERNET_DISCONNECTED') &&
      !message.includes('WebSocket connection to') &&
      !message.includes('net::ERR_NETWORK_CHANGED'),
  );
}

test('public demo hides upstream identity and fixed-node WebSocket routes', async ({ request }) => {
  test.skip(
    process.env.E2E_PUBLIC_DEMO !== 'true',
    'This boundary runs against docker-compose.demo.yml without the E2E overlay.',
  );

  const health = await request.get(`${API_URL}/actuator/health`);
  expect(health.ok()).toBeTruthy();
  expect(health.headers()['x-demo-upstream']).toBeUndefined();

  for (const route of ['/ws/app-1', '/ws/app-2']) {
    const response = await request.get(`${API_URL}${route}`);
    expect(response.status()).toBe(404);
    expect(response.headers()['x-demo-upstream']).toBeUndefined();
  }
});

test('demo is one-click, strict-headered, accessible, and keyboard operable', async ({ page }) => {
  await page.emulateMedia({ reducedMotion: 'reduce' });
  const diagnostics = monitor(page);
  const response = await page.goto('/');
  expect(response?.headers()['content-security-policy']).toContain("default-src 'self'");
  expect(response?.headers()['permissions-policy']).toContain('camera=()');
  await expect(page.getByRole('button', { name: 'Alice 데모 계정으로 바로 시작' })).toBeVisible();
  await assertNoSeriousAxeViolations(page);

  await page.getByRole('button', { name: 'Alice 데모 계정으로 바로 시작' }).click();
  await expect(page.getByText('Alice', { exact: true })).toBeVisible();
  const correctnessDetails = page.locator('details.correctness-details');
  const correctnessSummary = correctnessDetails.locator('summary');
  await correctnessSummary.focus();
  await page.keyboard.press('Enter');
  await expect(correctnessDetails).toHaveAttribute('open', '');
  await expect(page.getByText('DB commit → broadcast')).toBeVisible();
  await expect(page.getByText('두 경계에서 중복 제거')).toBeVisible();
  await expect(page.getByText('Cursor로 재연결')).toBeVisible();
  await assertNoSeriousAxeViolations(page);
  await page.keyboard.press('Space');
  await expect(correctnessDetails).not.toHaveAttribute('open', '');
  await assertNoSeriousAxeViolations(page);

  expect(diagnostics.pageErrors).toEqual([]);
  expect(diagnostics.consoleErrors).toEqual([]);
});

test('Alice creates a room and app-1 delivers to app-2 exactly once across recovery boundaries', async ({
  browser,
  request,
}) => {
  const alice = await signup(request, 'Alice');
  const bob = await signup(request, 'Bob');
  const aliceBrowser = await authenticatedPage(browser, alice);
  const roomId = await createDirectRoomThroughUi(aliceBrowser.page, bob.nickname);
  const bobBrowser = await authenticatedPage(browser, bob, alice.nickname);
  const aliceConnection = await connectStomp(alice.token, ALICE_NODE_WS_URL);
  const bobConnection = await connectStomp(bob.token, BOB_NODE_WS_URL);
  expect(aliceConnection.instanceId).toBe('app-1');
  expect(bobConnection.instanceId).toBe('app-2');
  expect(aliceConnection.instanceId).not.toBe(bobConnection.instanceId);
  const rawAlice = aliceConnection.client;
  const rawBob = bobConnection.client;
  const bobFrames: Array<{ clientMessageId: string; content: string }> = [];
  rawBob.subscribe(`/topic/room.${roomId}`, (frame) => {
    bobFrames.push(JSON.parse(frame.body) as { clientMessageId: string; content: string });
  });
  await bobBrowser.page.waitForTimeout(250);

  try {
    const crossNodeId = crypto.randomUUID();
    const crossNodeContent = `cross-node-${Date.now()}`;
    publish(rawAlice, roomId, crossNodeContent, crossNodeId);
    await expect
      .poll(() => bobFrames.filter((frame) => frame.clientMessageId === crossNodeId).length)
      .toBe(1);
    await expect(messageArticle(bobBrowser.page, crossNodeContent)).toHaveCount(1);

    const normalContent = `exactly-once-${Date.now()}`;
    await aliceBrowser.page.getByRole('textbox', { name: '메시지', exact: true }).fill(normalContent);
    await aliceBrowser.page.getByRole('button', { name: '보내기' }).click();
    await expect(messageArticle(aliceBrowser.page, normalContent)).toHaveAttribute(
      'data-status',
      'PERSISTED',
    );
    await expect(messageArticle(bobBrowser.page, normalContent)).toHaveCount(1);
    await expect.poll(() => bobFrames.filter((frame) => frame.content === normalContent).length).toBe(1);
    await bobBrowser.page.waitForTimeout(500);
    expect(bobFrames.filter((frame) => frame.content === normalContent)).toHaveLength(1);

    await bobBrowser.context.setOffline(true);
    await bobBrowser.page.waitForTimeout(300);
    const offlineContent = `offline-sync-${Date.now()}`;
    await aliceBrowser.page.getByRole('textbox', { name: '메시지', exact: true }).fill(offlineContent);
    await aliceBrowser.page.getByRole('button', { name: '보내기' }).click();
    await expect(messageArticle(aliceBrowser.page, offlineContent)).toHaveAttribute(
      'data-status',
      'PERSISTED',
    );
    expect(await messageArticle(bobBrowser.page, offlineContent).count()).toBe(0);
    await bobBrowser.context.setOffline(false);
    await expect(messageArticle(bobBrowser.page, offlineContent)).toHaveCount(1);

    const retryId = crypto.randomUUID();
    const retryContent = `same-client-id-${Date.now()}`;
    publish(rawAlice, roomId, retryContent, retryId);
    publish(rawAlice, roomId, retryContent, retryId);
    await expect(messageArticle(bobBrowser.page, retryContent)).toHaveCount(1);
    await expect
      .poll(() => count(request, `/api/demo/messages/${retryId}/count`))
      .toBe(1);
    await bobBrowser.page.waitForTimeout(700);
    expect(bobFrames.filter((frame) => frame.clientMessageId === retryId)).toHaveLength(1);

    const databaseFailureBefore = await count(request, '/api/demo/failures/database/count');
    await armFailure(request, 'database');
    const databaseId = crypto.randomUUID();
    const databaseContent = `db-before-broadcast-${Date.now()}`;
    publish(rawAlice, roomId, databaseContent, databaseId);
    await expect
      .poll(() => count(request, '/api/demo/failures/database/count'))
      .toBeGreaterThan(databaseFailureBefore);
    expect(await count(request, `/api/demo/messages/${databaseId}/count`)).toBe(0);
    expect(bobFrames.filter((frame) => frame.clientMessageId === databaseId)).toHaveLength(0);
    await expect(messageArticle(bobBrowser.page, databaseContent)).toHaveCount(1);
    await expect
      .poll(() => count(request, `/api/demo/messages/${databaseId}/count`))
      .toBe(1);
    expect(bobFrames.filter((frame) => frame.clientMessageId === databaseId)).toHaveLength(1);

    const redisFailureBefore = await count(request, '/api/demo/failures/redis/count');
    await armFailure(request, 'redis');
    const redisId = crypto.randomUUID();
    const redisContent = `redis-redelivery-${Date.now()}`;
    publish(rawAlice, roomId, redisContent, redisId);
    await expect
      .poll(() => count(request, '/api/demo/failures/redis/count'))
      .toBeGreaterThan(redisFailureBefore);
    expect(await count(request, `/api/demo/messages/${redisId}/count`)).toBe(1);
    expect(bobFrames.filter((frame) => frame.clientMessageId === redisId)).toHaveLength(0);
    await expect(messageArticle(bobBrowser.page, redisContent)).toHaveCount(1);
    await bobBrowser.page.waitForTimeout(700);
    expect(bobFrames.filter((frame) => frame.clientMessageId === redisId)).toHaveLength(1);
    expect(await count(request, `/api/demo/messages/${redisId}/count`)).toBe(1);

    await bobBrowser.page.reload();
    await expect(bobBrowser.page.getByRole('heading', { name: alice.nickname })).toBeVisible();
    for (const content of [
      crossNodeContent,
      normalContent,
      offlineContent,
      retryContent,
      databaseContent,
      redisContent,
    ]) {
      await expect(messageArticle(bobBrowser.page, content)).toHaveCount(1);
    }
    await assertNoSeriousAxeViolations(bobBrowser.page);
    if (process.env.E2E_CAPTURE_PATH) {
      await bobBrowser.page.screenshot({
        path: process.env.E2E_CAPTURE_PATH,
        animations: 'disabled',
      });
    }

    expect(aliceBrowser.diagnostics.pageErrors).toEqual([]);
    expect(bobBrowser.diagnostics.pageErrors).toEqual([]);
    expect(unexpectedConsoleErrors(aliceBrowser.diagnostics.consoleErrors)).toEqual([]);
    expect(unexpectedConsoleErrors(bobBrowser.diagnostics.consoleErrors)).toEqual([]);
  } finally {
    await rawAlice.deactivate();
    await rawBob.deactivate();
    await aliceBrowser.context.close();
    await bobBrowser.context.close();
  }
});
