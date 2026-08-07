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
  await expect(page.getByRole('button', { name: '가입하지 않고 둘러보기' })).toBeVisible();
  await assertNoSeriousAxeViolations(page);

  await page.getByRole('button', { name: '가입하지 않고 둘러보기' }).click();
  /*
   * 둘러보기는 데모 인물을 돌아가며 내준다 — 누가 될지 정해져 있지 않다.
   * 전에는 무조건 Alice였고, 그래서 창을 두 개 열어도 둘 다 같은 사람이라
   * 메시지를 주고받을 수 없었다. 여기서는 "누군가로 들어와졌다"만 본다.
   */
  await expect(page.locator('.utility-bar strong')).not.toBeEmpty();
  /*
   * 여기 있던 'How it stays correct' 서랍은 제품 안의 포트폴리오 글이라 지웠다.
   * 검사하던 것(키보드로 조작되는가 · 조작 뒤에도 axe가 깨끗한가)은 그대로 두고,
   * 실제로 있는 컨트롤인 대화 목록으로 옮긴다.
   */
  const room = page.getByRole('button', { name: /제품팀 스탠드업/ });
  await room.focus();
  await page.keyboard.press('Enter');
  await expect(page.getByRole('heading', { level: 1 })).toHaveText('제품팀 스탠드업');
  await assertNoSeriousAxeViolations(page);

  // 날짜 구분선과 연속 메시지 묶기 — 메신저의 관례가 실제로 그려지는지
  await expect(page.locator('.day-divider')).not.toHaveCount(0);
  await expect(page.locator('.message-row.is-run')).not.toHaveCount(0);
  // 이어지는 줄은 이름을 반복하지 않는다
  await expect(page.locator('.message-row.is-run .message-sender')).toHaveCount(0);
  // 시각은 묶음의 마지막 줄에만 붙는다
  await expect(page.locator('.message-row.is-run:not(.is-tail) time')).toHaveCount(0);

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

/*
 * 이 데모의 존재 이유.
 *
 * 전에는 둘러보기가 언제나 같은 사람(alice@demo.local)으로 들어가서, 창을 두 개 열어도
 * 둘 다 같은 사람이었다. 실시간 전달이 이 제품의 전부인데 방문자는 그걸 한 번도 보지
 * 못했고, 그래서 "실제 서비스가 아니라 보여주기 식"으로 읽혔다.
 *
 * 이제 인물을 돌아가며 내준다. 이 테스트는 그 약속을 지킨다 —
 * 두 창이 서로 다른 사람이고, 한쪽에서 보낸 것이 다른 쪽에 도착한다.
 */
test('둘러보기로 연 두 창은 서로 다른 사람이고 메시지가 실제로 오간다', async ({ browser }) => {
  const openDemo = async () => {
    const page = await (await browser.newContext()).newPage();
    await page.goto('/');
    await page.getByRole('button', { name: '가입하지 않고 둘러보기' }).click();
    await expect(page.locator('.utility-bar strong')).not.toBeEmpty();
    const who = await page.locator('.utility-bar strong').innerText();
    await page.getByRole('button', { name: /제품팀 스탠드업/ }).click();
    await page.locator('.message-row').first().waitFor();
    return { page, who };
  };

  const sender = await openDemo();
  const receiver = await openDemo();
  expect(sender.who).not.toBe(receiver.who);

  /*
   * 받는 쪽의 구독이 설 때까지 기다린다. 열자마자 보내면 구독 전이라 밀려 오지 않고
   * 재접속 보충 조회로만 채워진다 — 실시간으로 도착하는지를 보려면 기다려야 한다.
   */
  await expect(receiver.page.locator('.conversation-header p').first()).toContainText('명 온라인');

  const text = `실시간 확인 ${Date.now()}`;
  await sender.page.getByPlaceholder('메시지를 입력하세요').fill(text);
  await sender.page.keyboard.press('Enter');

  await expect(receiver.page.getByText(text)).toBeVisible({ timeout: 15_000 });
  // 보낸 쪽은 DB에 남은 뒤에야 '전달 완료'가 된다
  await expect(sender.page.locator('.message-row').last()).toContainText('전달 완료');
});

test('그룹 대화에는 초대 링크가 있고 1:1에는 없다', async ({ page }) => {
  await page.goto('/');
  await page.getByRole('button', { name: '가입하지 않고 둘러보기' }).click();
  await expect(page.locator('.utility-bar strong')).not.toBeEmpty();

  await page.getByRole('button', { name: /제품팀 스탠드업/ }).click();
  const copy = page.getByRole('button', { name: '초대 링크 복사' });
  await expect(copy).toBeVisible();

  // 서버가 1:1 방 참여를 거부하므로 링크를 주지 않는다
  const direct = page.locator('.room-item, aside li button').filter({ hasText: /^(?!.*스탠드업|.*배포 준비|.*디자인 리뷰|.*점심).*$/ });
  if (await direct.count()) {
    await direct.first().click();
    await page.locator('.message-row').first().waitFor();
    const header = await page.locator('.conversation-header').innerText();
    if (!/스탠드업|배포 준비|디자인 리뷰|점심/.test(header)) {
      await expect(page.getByRole('button', { name: '초대 링크 복사' })).toHaveCount(0);
    }
  }
});
