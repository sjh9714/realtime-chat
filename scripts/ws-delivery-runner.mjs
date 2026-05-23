#!/usr/bin/env node

import { mkdirSync, writeFileSync } from "node:fs";
import { join } from "node:path";
import { execFileSync } from "node:child_process";
import { randomUUID } from "node:crypto";

const DEFAULT_BASE_URL = "http://localhost:8080";
const DEFAULT_WS_URL = "ws://localhost:8080/ws";

function usage() {
  console.error(
    [
      "Usage:",
      "  node scripts/ws-delivery-runner.mjs [options]",
      "",
      "Options:",
      "  --base-url http://localhost:8080",
      "  --ws-url ws://localhost:8080/ws",
      "  --base http://localhost:8080        Alias for --base-url",
      "  --ws ws://localhost:8080/ws          Alias for --ws-url; comma-separated URLs are allowed",
      "  --users 3",
      "  --rooms 1                            Number of group rooms to create",
      "  --users-per-room 50                  Derive total users from rooms x users-per-room",
      "  --messages-per-user 1",
      "  --messages 1                         Alias for --messages-per-user",
      "  --senders 3                          Number of users that send messages",
      "  --senders-per-room 1                 Multi-room sender count per room",
      "  --send-interval-ms 50                Delay between SEND frames from the runner",
      "  --drain-ms 3000",
      "  --subscribe-receipt-timeout-ms 5000",
      "  --status-subscribe-settle-ms 250      Delay after CONNECT/SUBSCRIBE setup before SEND frames",
      "  --require-room-receipts true          Require room-topic SUBSCRIBE receipts before SEND frames",
      "  --require-status-receipts true        Also require ACK/ERROR/PERSISTED SUBSCRIBE receipts",
      "  --mixed-http-probes true              Optional HTTP room/message/read probes after WebSocket drain",
      "  --http-probe-users-per-room 1         Users per room to run HTTP probes with",
      "  --out-dir artifacts/ws/<run-id>",
      "",
      "The runner signs up test users, creates one or more group rooms, waits for every",
      "WebSocket client to CONNECT, sends room/status SUBSCRIBE frames, waits the settle delay, then writes:",
      "  members.jsonl, send.jsonl, receive.jsonl, status.jsonl, http.jsonl",
      "",
      "The runner also writes summary.json using scripts/delivery-matrix.mjs.",
      "Multi-room output is candidate evidence only until the run artifact is reviewed and documented.",
    ].join("\n"),
  );
}

function parseArgs(argv) {
  if (argv.length === 1 && (argv[0] === "--help" || argv[0] === "-h")) {
    usage();
    process.exit(0);
  }

  const args = {
    baseUrl: DEFAULT_BASE_URL,
    wsUrls: [DEFAULT_WS_URL],
    users: 3,
    rooms: 1,
    usersPerRoom: null,
    messagesPerUser: 1,
    senders: null,
    sendersPerRoom: null,
    sendIntervalMs: 50,
    drainMs: 3000,
    subscribeReceiptTimeoutMs: 5000,
    statusSubscribeSettleMs: 250,
    requireRoomReceipts: false,
    requireStatusReceipts: false,
    mixedHttpProbes: false,
    httpProbeUsersPerRoom: 1,
    outDir: null,
  };

  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || !value) {
      usage();
      process.exit(2);
    }
    switch (key.slice(2)) {
      case "base":
      case "base-url":
        args.baseUrl = value;
        break;
      case "ws":
      case "ws-url":
        args.wsUrls = value
          .split(",")
          .map((url) => url.trim())
          .filter(Boolean);
        break;
      case "users":
        args.users = positiveInteger(value, "users");
        break;
      case "rooms":
        args.rooms = positiveInteger(value, "rooms");
        break;
      case "users-per-room":
        args.usersPerRoom = positiveInteger(value, "users-per-room");
        break;
      case "messages":
      case "messages-per-user":
        args.messagesPerUser = positiveInteger(value, "messages-per-user");
        break;
      case "senders":
        args.senders = positiveInteger(value, "senders");
        break;
      case "senders-per-room":
        args.sendersPerRoom = positiveInteger(value, "senders-per-room");
        break;
      case "send-interval-ms":
        args.sendIntervalMs = positiveInteger(value, "send-interval-ms");
        break;
      case "drain-ms":
        args.drainMs = positiveInteger(value, "drain-ms");
        break;
      case "subscribe-receipt-timeout-ms":
        args.subscribeReceiptTimeoutMs = positiveInteger(
          value,
          "subscribe-receipt-timeout-ms",
        );
        break;
      case "status-subscribe-settle-ms":
        args.statusSubscribeSettleMs = nonNegativeInteger(
          value,
          "status-subscribe-settle-ms",
        );
        break;
      case "require-room-receipts":
        args.requireRoomReceipts = parseBoolean(value, "require-room-receipts");
        break;
      case "require-status-receipts":
        args.requireStatusReceipts = parseBoolean(value, "require-status-receipts");
        break;
      case "mixed-http-probes":
        args.mixedHttpProbes = parseBoolean(value, "mixed-http-probes");
        break;
      case "http-probe-users-per-room":
        args.httpProbeUsersPerRoom = positiveInteger(
          value,
          "http-probe-users-per-room",
        );
        break;
      case "out-dir":
        args.outDir = value;
        break;
      default:
        throw new Error(`Unknown option: ${key}`);
    }
  }

  if (args.usersPerRoom !== null) {
    args.users = args.rooms * args.usersPerRoom;
  }
  if (args.users < 2) {
    throw new Error("--users must be at least 2 to measure recipient delivery");
  }
  if (args.wsUrls.length === 0) {
    throw new Error("--ws-url must include at least one WebSocket URL");
  }
  const usersPerRoom = resolveUsersPerRoom(args);
  const sendersPerRoom = resolveSendersPerRoom(args, usersPerRoom);
  if (usersPerRoom < 2) {
    throw new Error("each room must include at least 2 users to measure delivery");
  }
  if (sendersPerRoom > usersPerRoom) {
    throw new Error("--senders-per-room cannot be greater than users per room");
  }
  if (args.httpProbeUsersPerRoom > usersPerRoom) {
    throw new Error("--http-probe-users-per-room cannot be greater than users per room");
  }

  return args;
}

function positiveInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed <= 0) {
    throw new Error(`--${name} must be a positive integer`);
  }
  return parsed;
}

function nonNegativeInteger(value, name) {
  const parsed = Number(value);
  if (!Number.isInteger(parsed) || parsed < 0) {
    throw new Error(`--${name} must be a non-negative integer`);
  }
  return parsed;
}

function parseBoolean(value, name) {
  if (value === "true") {
    return true;
  }
  if (value === "false") {
    return false;
  }
  throw new Error(`--${name} must be true or false`);
}

async function main() {
  const options = parseArgs(process.argv.slice(2));
  const runId = `receiver-matrix-${Date.now()}-${Math.floor(Math.random() * 100000)}`;
  const outDir = options.outDir ?? join("artifacts", "ws", runId);
  const usersPerRoom = resolveUsersPerRoom(options);
  const sendersPerRoom = resolveSendersPerRoom(options, usersPerRoom);

  const users = await createUsers(options.baseUrl, runId, options.users);
  const userGroups = chunkUsers(users, options.rooms, usersPerRoom);
  const rooms = await createRooms(options.baseUrl, runId, userGroups);
  const logs = {
    members: rooms.flatMap((room) =>
      room.users.map((user) => ({
        runId,
        roomId: room.id,
        roomNumber: room.number,
        userId: user.userId,
      })),
    ),
    send: [],
    receive: [],
    status: [],
    http: [],
  };
  const clientSpecs = rooms.flatMap((room) =>
    room.users.map((user) => ({
      room,
      user,
    })),
  );

  const clients = clientSpecs.map((spec, index) => {
    const client = connectClient(
        options.wsUrls[index % options.wsUrls.length],
        spec.user,
        spec.room.id,
        runId,
        logs,
        {
          subscribeReceiptTimeoutMs: options.subscribeReceiptTimeoutMs,
          requireRoomReceipts: options.requireRoomReceipts,
          requireStatusReceipts: options.requireStatusReceipts,
        },
      );
    return {
      ...client,
      roomId: spec.room.id,
      roomNumber: spec.room.number,
    };
  });

  let runError = null;

  try {
    await Promise.all(clients.map((client) => client.connected));
    if (options.statusSubscribeSettleMs > 0) {
      await sleep(options.statusSubscribeSettleMs);
    }

    const sendJobs = buildSendJobs(clients, rooms, sendersPerRoom, options.messagesPerUser);
    for (const job of sendJobs) {
      job.client.sendMessage(runId, job.sequence, logs);
      await sleep(options.sendIntervalMs);
    }

    await sleep(options.drainMs);
    if (options.mixedHttpProbes) {
      await runMixedHttpProbes({
        baseUrl: options.baseUrl,
        rooms,
        usersPerRoom: options.httpProbeUsersPerRoom,
        logs,
        runId,
      });
    }
  } catch (error) {
    runError = error;
  } finally {
    clients.forEach((client) => client.close());
  }

  await enrichPersistedMessageIds({
    baseUrl: options.baseUrl,
    rooms,
    logs,
  });
  writeJsonLines(outDir, "members.jsonl", logs.members);
  writeJsonLines(outDir, "send.jsonl", logs.send);
  writeJsonLines(outDir, "receive.jsonl", logs.receive);
  writeJsonLines(outDir, "status.jsonl", logs.status);
  writeJsonLines(outDir, "http.jsonl", logs.http);

  if (runError) {
    const failure = {
      runId,
      rooms: options.rooms,
      roomIds: rooms.map((room) => room.id),
      users: users.length,
      usersPerRoom,
      sendersPerRoom,
      sendIntervalMs: options.sendIntervalMs,
      messagesAttempted: logs.send.length,
      deliveriesObserved: logs.receive.length,
      statusesObserved: logs.status.length,
      httpRequestsObserved: logs.http.length,
      outDir,
      subscriptionBarrier: subscriptionBarrierLabel(options),
      readiness: clients.map((client) => client.readiness()),
      error: runError.message,
      next: `Inspect ${outDir}/status.jsonl and ${outDir}/failure.json before treating this run as evidence.`,
    };
    writeJson(outDir, "failure.json", failure);
    console.error(JSON.stringify(failure, null, 2));
    throw runError;
  }

  const summary = summarizeDelivery(outDir);
  const manifest = buildManifest({
    runId,
    outDir,
    options,
    rooms,
    users,
    usersPerRoom,
    sendersPerRoom,
    logs,
    summary,
  });
  writeJson(outDir, "manifest.json", manifest);
  const nextCommand = [
    "node scripts/delivery-matrix.mjs",
    `--members ${outDir}/members.jsonl`,
    `--send ${outDir}/send.jsonl`,
    `--receive ${outDir}/receive.jsonl`,
    `--status ${outDir}/status.jsonl`,
    `--http ${outDir}/http.jsonl`,
  ].join(" ");

  console.log(
    JSON.stringify(
      {
        runId,
        rooms: options.rooms,
        roomIds: rooms.map((room) => room.id),
        users: users.length,
        usersPerRoom,
        sendersPerRoom,
        sendIntervalMs: options.sendIntervalMs,
        messagesSent: logs.send.length,
        deliveriesObserved: logs.receive.length,
        statusesObserved: logs.status.length,
        httpRequestsObserved: logs.http.length,
        outDir,
        subscriptionBarrier: subscriptionBarrierLabel(options),
        statusSubscribeSettleMs: options.statusSubscribeSettleMs,
        readiness: clients.map((client) => client.readiness()),
        summary,
        manifest: `${outDir}/manifest.json`,
        next: nextCommand,
      },
      null,
      2,
    ),
  );
}

function buildManifest({
  runId,
  outDir,
  options,
  rooms,
  users,
  usersPerRoom,
  sendersPerRoom,
  logs,
  summary,
}) {
  return {
    schemaVersion: 1,
    artifactType: "websocket-delivery-evidence",
    claimBoundary: {
      status: "시나리오 검증",
      scope:
        "Local WebSocket receiver-matrix artifact. Do not promote to public benchmark without separate documented review.",
      acceptableDiagnostics: [
        "statuslessSends",
        "failedSends",
        "missingDeliveries",
        "duplicateDeliveries",
        "unexpectedDeliveries",
      ],
    },
    generatedAt: new Date().toISOString(),
    runId,
    outDir,
    options: {
      baseUrl: options.baseUrl,
      wsUrls: options.wsUrls,
      users: options.users,
      rooms: options.rooms,
      usersPerRoom,
      messagesPerUser: options.messagesPerUser,
      senders: options.senders,
      sendersPerRoom,
      sendIntervalMs: options.sendIntervalMs,
      drainMs: options.drainMs,
      subscribeReceiptTimeoutMs: options.subscribeReceiptTimeoutMs,
      statusSubscribeSettleMs: options.statusSubscribeSettleMs,
      requireRoomReceipts: options.requireRoomReceipts,
      requireStatusReceipts: options.requireStatusReceipts,
      mixedHttpProbes: options.mixedHttpProbes,
      httpProbeUsersPerRoom: options.httpProbeUsersPerRoom,
    },
    expected: {
      sessions: users.length,
      rooms: rooms.length,
      usersPerRoom,
      sendersPerRoom,
      messagesPerUser: options.messagesPerUser,
      messagesAttempted: logs.send.length,
      roomIds: rooms.map((room) => room.id),
      roomMemberCounts: Object.fromEntries(
        rooms.map((room) => [room.id, room.users.length]),
      ),
      mixedHttpProbesIncluded: options.mixedHttpProbes,
    },
    observed: {
      messagesAttempted: logs.send.length,
      deliveriesObserved: logs.receive.length,
      statusesObserved: logs.status.length,
      httpRequestsObserved: logs.http.length,
      summary: {
        expectedDeliveries: summary.expectedDeliveries,
        actualUniqueDeliveries: summary.actualUniqueDeliveries,
        missingDeliveries: summary.missingDeliveries,
        duplicateDeliveries: summary.duplicateDeliveries,
        unexpectedDeliveries: summary.unexpectedDeliveries,
        sendStatus: summary.sendStatus,
        mixedHttp: summary.mixedHttp,
      },
    },
    environment: {
      nodeVersion: process.version,
      platform: process.platform,
      arch: process.arch,
      pid: process.pid,
      cwd: process.cwd(),
      timezone: Intl.DateTimeFormat().resolvedOptions().timeZone,
    },
    files: {
      members: "members.jsonl",
      send: "send.jsonl",
      receive: "receive.jsonl",
      status: "status.jsonl",
      http: "http.jsonl",
      summary: "summary.json",
    },
  };
}

async function createUsers(baseUrl, runId, count) {
  const users = [];
  for (let index = 0; index < count; index += 1) {
    const response = await postJson(`${baseUrl}/api/auth/signup`, {
      email: `${runId}-${index}@example.com`,
      password: "password123",
      nickname: `MatrixUser${index}`,
    });
    users.push(response);
  }
  return users;
}

async function createRooms(baseUrl, runId, userGroups) {
  const rooms = [];
  for (let index = 0; index < userGroups.length; index += 1) {
    const users = userGroups[index];
    const response = await createGroupRoom(baseUrl, runId, users, index + 1);
    rooms.push({
      id: response.id,
      number: index + 1,
      users,
    });
  }
  return rooms;
}

async function createGroupRoom(baseUrl, runId, users, roomNumber = 1) {
  return postJson(
    `${baseUrl}/api/rooms/group`,
    {
      name: `receiver-matrix-${runId}-room-${roomNumber}`,
      memberIds: users.slice(1).map((user) => user.userId),
    },
    users[0].token,
  );
}

function resolveUsersPerRoom(options) {
  if (options.usersPerRoom !== null) {
    return options.usersPerRoom;
  }
  if (options.users % options.rooms !== 0) {
    throw new Error("--users must be divisible by --rooms or use --users-per-room");
  }
  return options.users / options.rooms;
}

function resolveSendersPerRoom(options, usersPerRoom) {
  if (options.sendersPerRoom !== null) {
    return options.sendersPerRoom;
  }
  if (options.rooms > 1 && options.senders !== null) {
    return options.senders;
  }
  if (options.rooms === 1 && options.senders !== null) {
    return options.senders;
  }
  return usersPerRoom;
}

function chunkUsers(users, rooms, usersPerRoom) {
  const groups = [];
  for (let roomIndex = 0; roomIndex < rooms; roomIndex += 1) {
    const start = roomIndex * usersPerRoom;
    groups.push(users.slice(start, start + usersPerRoom));
  }
  return groups;
}

function buildSendJobs(clients, rooms, sendersPerRoom, messagesPerUser) {
  const clientsByRoom = new Map();
  for (const client of clients) {
    if (!clientsByRoom.has(client.roomId)) {
      clientsByRoom.set(client.roomId, []);
    }
    clientsByRoom.get(client.roomId).push(client);
  }

  const senderGroups = rooms.map((room) =>
    (clientsByRoom.get(room.id) ?? []).slice(0, sendersPerRoom),
  );
  const jobs = [];
  for (let messageIndex = 0; messageIndex < messagesPerUser; messageIndex += 1) {
    for (let senderIndex = 0; senderIndex < sendersPerRoom; senderIndex += 1) {
      for (const senderGroup of senderGroups) {
        const client = senderGroup[senderIndex];
        if (client) {
          jobs.push({ client, sequence: messageIndex });
        }
      }
    }
  }
  return jobs;
}

async function enrichPersistedMessageIds({ baseUrl, rooms, logs }) {
  const persistedByMessage = await persistedMessageIndex({ baseUrl, rooms, logs });

  for (const row of [...logs.send, ...logs.receive]) {
    const persisted =
      persistedByMessage.byMessageKey.get(keyOfMessage(row.roomId, row.clientMessageId)) ??
      (row.messageKey ? persistedByMessage.byServerMessageKey.get(row.messageKey) : null) ??
      persistedByMessage.byContent.get(contentKey(row));
    if (!persisted) {
      continue;
    }
    row.messageId = persisted.messageId;
    row.messageIdSource = persisted.source;
    if (persisted.messageKey && !row.messageKey) {
      row.messageKey = persisted.messageKey;
    }
  }
}

async function persistedMessageIndex({ baseUrl, rooms, logs }) {
  const byMessageKey = new Map();
  const byServerMessageKey = new Map();
  const byContent = new Map();

  for (const status of logs.status) {
    if (!status.roomId || !status.clientMessageId || !Number.isFinite(Number(status.messageId))) {
      continue;
    }
    byMessageKey.set(keyOfMessage(status.roomId, status.clientMessageId), {
      messageId: Number(status.messageId),
      messageKey: status.messageKey,
      source: "status",
    });
  }

  for (const room of rooms) {
    const expectedMessages = logs.send.filter((row) => row.roomId === room.id).length;
    if (expectedMessages === 0) {
      continue;
    }
    const history = await requestJson(
      "GET",
      new URL(`/api/rooms/${room.id}/messages?size=${expectedMessages}`, baseUrl),
      room.users[0].token,
    );
    const messages = Array.isArray(history.body?.messages) ? history.body.messages : [];
    for (const message of messages) {
      const messageId = Number(message.id ?? message.messageId);
      if (!Number.isFinite(messageId)) {
        continue;
      }
      const persisted = {
        messageId,
        messageKey: message.messageKey,
        source: "history",
      };
      if (message.messageKey) {
        byServerMessageKey.set(message.messageKey, persisted);
      }
      byContent.set(
        contentKey({
          runId: runIdFromContent(message.content),
          roomSequence: sequenceFromContent(message.content),
          senderUserId: message.senderId,
        }),
        persisted,
      );
    }
  }

  return { byMessageKey, byServerMessageKey, byContent };
}

function keyOfMessage(roomId, clientMessageId) {
  return `${roomId}\u0000${clientMessageId}`;
}

function contentKey(row) {
  return `${row.runId}\u0000${row.roomSequence}\u0000${row.senderUserId}`;
}

async function postJson(url, payload, token = null) {
  const headers = {
    "Content-Type": "application/json",
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  const response = await fetch(url, {
    method: "POST",
    headers,
    body: JSON.stringify(payload),
  });

  const text = await response.text();
  if (!response.ok) {
    throw new Error(`POST ${url} failed: status=${response.status}, body=${text}`);
  }

  return text ? JSON.parse(text) : {};
}

async function requestJson(method, url, token, payload = null) {
  const startedAtMs = Date.now();
  const headers = {
    "Content-Type": "application/json",
  };
  if (token) {
    headers.Authorization = `Bearer ${token}`;
  }

  try {
    const response = await fetch(url, {
      method,
      headers,
      body: payload === null ? undefined : JSON.stringify(payload),
    });
    const text = await response.text();
    const durationMs = Date.now() - startedAtMs;
    return {
      status: response.status,
      ok: response.ok,
      body: safeJson(text),
      text,
      startedAtMs,
      durationMs,
    };
  } catch (error) {
    return {
      status: null,
      ok: false,
      body: null,
      text: "",
      startedAtMs,
      durationMs: Date.now() - startedAtMs,
      error: error.message,
    };
  }
}

async function runMixedHttpProbes({ baseUrl, rooms, usersPerRoom, logs, runId }) {
  for (const room of rooms) {
    for (const user of room.users.slice(0, usersPerRoom)) {
      await recordHttpProbe({
        baseUrl,
        logs,
        runId,
        roomId: room.id,
        userId: user.userId,
        token: user.token,
        operation: "rooms_list",
        method: "GET",
        path: "/api/rooms",
      });
      const history = await recordHttpProbe({
        baseUrl,
        logs,
        runId,
        roomId: room.id,
        userId: user.userId,
        token: user.token,
        operation: "message_history",
        method: "GET",
        path: `/api/rooms/${room.id}/messages?size=20`,
      });
      const latestMessageId = latestMessageIdFrom(history.body);
      if (latestMessageId !== null) {
        await recordHttpProbe({
          baseUrl,
          logs,
          runId,
          roomId: room.id,
          userId: user.userId,
          token: user.token,
          operation: "read_receipt",
          method: "POST",
          path: `/api/rooms/${room.id}/read`,
          payload: { lastReadMessageId: latestMessageId },
          latestMessageId,
        });
      }
    }
  }
}

async function recordHttpProbe({
  baseUrl,
  logs,
  runId,
  roomId,
  userId,
  token,
  operation,
  method,
  path,
  payload = null,
  latestMessageId = null,
}) {
  const url = new URL(path, baseUrl);
  const response = await requestJson(method, url, token, payload);
  logs.http.push({
    runId,
    roomId,
    userId,
    operation,
    method,
    path,
    status: response.status,
    ok: response.ok,
    startedAtMs: response.startedAtMs,
    durationMs: response.durationMs,
    latestMessageId,
    error: response.error,
  });
  return response;
}

function latestMessageIdFrom(body) {
  const candidates = Array.isArray(body)
    ? body
    : (body?.messages ?? body?.content ?? body?.items ?? body?.data ?? []);
  const ids = candidates
    .map((message) => Number(message.id ?? message.messageId))
    .filter(Number.isFinite);
  if (ids.length === 0) {
    return null;
  }
  return Math.max(...ids);
}

function connectClient(wsUrl, user, roomId, runId, logs, readinessOptions) {
  const socket = new WebSocket(wsUrl);
  let resolveConnected;
  let rejectConnected;
  let hasConnected = false;
  let isReady = false;
  let subscriptionTimeout = null;
  const requiredPendingReceipts = new Set();
  const optionalPendingReceipts = new Set();
  const receiptDestinations = new Map();
  let expectedRequiredReceiptCount = 0;
  let expectedOptionalReceiptCount = 0;
  const connected = new Promise((resolve, reject) => {
    resolveConnected = resolve;
    rejectConnected = reject;
  });

  socket.addEventListener("open", () => {
    socket.send(
      stompFrame("CONNECT", {
        "accept-version": "1.2",
        Authorization: `Bearer ${user.token}`,
      }),
    );
  });

  socket.addEventListener("message", (event) => {
    for (const rawFrame of splitFrames(event.data)) {
      const frame = parseFrame(rawFrame);
      if (!frame) {
        continue;
      }

      if (frame.command === "CONNECTED") {
        for (const subscription of [
          {
            id: `room-${roomId}-${user.userId}`,
            destination: `/topic/room.${roomId}`,
            requiredReceipt: readinessOptions.requireRoomReceipts,
          },
          {
            id: `ack-${roomId}-${user.userId}`,
            destination: "/user/queue/messages/ack",
            requiredReceipt: readinessOptions.requireStatusReceipts,
          },
          {
            id: `error-${roomId}-${user.userId}`,
            destination: "/user/queue/messages/error",
            requiredReceipt: readinessOptions.requireStatusReceipts,
          },
          {
            id: `persisted-${roomId}-${user.userId}`,
            destination: "/user/queue/messages/persisted",
            requiredReceipt: readinessOptions.requireStatusReceipts,
          },
        ]) {
          const receiptId = `subscribe-${subscription.id}`;
          if (subscription.requiredReceipt) {
            requiredPendingReceipts.add(receiptId);
            expectedRequiredReceiptCount += 1;
          } else {
            optionalPendingReceipts.add(receiptId);
            expectedOptionalReceiptCount += 1;
          }
          receiptDestinations.set(receiptId, subscription.destination);
          socket.send(
            stompFrame("SUBSCRIBE", {
              id: subscription.id,
              destination: subscription.destination,
              receipt: receiptId,
            }),
          );
        }
        hasConnected = true;
        if (expectedRequiredReceiptCount > 0) {
          subscriptionTimeout = setTimeout(() => {
            rejectConnected(
              new Error(
                `Timed out waiting for required SUBSCRIBE receipts for userId=${user.userId}: received=${
                  expectedRequiredReceiptCount - requiredPendingReceipts.size
                }/${expectedRequiredReceiptCount}, pending=${[
                  ...requiredPendingReceipts,
                ].join(", ")}`,
              ),
            );
          }, readinessOptions.subscribeReceiptTimeoutMs);
        }
        resolveWhenSubscribed();
        continue;
      }

      if (frame.command === "RECEIPT") {
        const receiptId = frame.headers["receipt-id"];
        const wasRequired = requiredPendingReceipts.delete(receiptId);
        const wasOptional = optionalPendingReceipts.delete(receiptId);
        if (wasRequired || wasOptional) {
          logs.status.push({
            runId,
            roomId,
            userId: user.userId,
            status: "subscription_receipt",
            receiptId,
            requiredForSendBarrier: wasRequired,
            destination: receiptDestinations.get(receiptId),
            observedAtMs: Date.now(),
          });
          receiptDestinations.delete(receiptId);
          resolveWhenSubscribed();
        }
        continue;
      }

      if (frame.command === "ERROR") {
        const error = new Error(`STOMP ERROR for userId=${user.userId}: ${frame.body}`);
        logs.status.push({
          runId,
          roomId,
          userId: user.userId,
          status: "stomp_error",
          reason: frame.body,
          observedAtMs: Date.now(),
        });
        if (!hasConnected) {
          rejectConnected(error);
        }
        continue;
      }

      if (frame.command === "MESSAGE") {
        const body = safeJson(frame.body);
        const publishStatus = normalizePublishStatus(body?.status);
        if (publishStatus && body?.clientMessageId) {
          logs.status.push({
            runId,
            roomId: body.roomId ?? roomId,
            userId: user.userId,
            clientMessageId: body.clientMessageId,
            messageKey: body.messageKey,
            messageId: body.messageId,
            status: publishStatus,
            serverStatus: body.status,
            reason: body.reason,
            destination: frame.headers.destination,
            observedAtMs: Date.now(),
          });
        }
        if (
          body?.clientMessageId &&
          frame.headers.destination === `/topic/room.${roomId}` &&
          body?.roomId === roomId &&
          body?.senderId !== undefined &&
          String(body.senderId) !== String(user.userId)
        ) {
          logs.receive.push({
            runId: body.content?.split(":")[0],
            roomId,
            receiverUserId: user.userId,
            senderUserId: body.senderId,
            clientMessageId: body.clientMessageId,
            messageKey: body.messageKey,
            roomSequence: sequenceFromContent(body.content),
            receivedAtMs: Date.now(),
          });
        }
      }
    }
  });

  socket.addEventListener("error", (event) => {
    if (!isReady) {
      rejectConnected(new Error(`WebSocket error for userId=${user.userId}: ${event.type}`));
    }
  });

  socket.addEventListener("close", () => {
    if (subscriptionTimeout) {
      clearTimeout(subscriptionTimeout);
    }
    if (!isReady) {
      rejectConnected(
        new Error(`WebSocket closed before subscriptions were ready for userId=${user.userId}`),
      );
    }
  });

  function resolveWhenSubscribed() {
    if (hasConnected && requiredPendingReceipts.size === 0 && !isReady) {
      isReady = true;
      if (subscriptionTimeout) {
        clearTimeout(subscriptionTimeout);
      }
      resolveConnected();
    }
  }

  return {
    user,
    connected,
    sendMessage(runId, sequence, targetLogs) {
      const clientMessageId = randomUUID();
      const content = `${runId}:${sequence}:${user.userId}`;
      targetLogs.send.push({
        runId,
        roomId,
        senderUserId: user.userId,
        clientMessageId,
        roomSequence: sequence,
        sendStartedAtMs: Date.now(),
        payloadBytes: Buffer.byteLength(content, "utf8"),
      });
      socket.send(
        stompFrame(
          "SEND",
          {
            destination: "/app/chat.send",
            "content-type": "application/json",
          },
          JSON.stringify({
            clientMessageId,
            roomId,
            content,
            type: "TEXT",
          }),
        ),
      );
    },
    close() {
      if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
        socket.close();
      }
    },
    readiness() {
      return {
        userId: user.userId,
        hasConnected,
        isReady,
        requiredReceiptCount: expectedRequiredReceiptCount,
        receivedRequiredReceiptCount:
          expectedRequiredReceiptCount - requiredPendingReceipts.size,
        optionalReceiptCount: expectedOptionalReceiptCount,
        receivedOptionalReceiptCount:
          expectedOptionalReceiptCount - optionalPendingReceipts.size,
        pendingRequiredReceipts: [...requiredPendingReceipts].map((receiptId) => ({
          receiptId,
          destination: receiptDestinations.get(receiptId),
        })),
        pendingOptionalReceipts: [...optionalPendingReceipts].map((receiptId) => ({
          receiptId,
          destination: receiptDestinations.get(receiptId),
        })),
      };
    },
  };
}

function subscriptionBarrierLabel(options) {
  if (options.requireRoomReceipts && options.requireStatusReceipts) {
    return "CONNECTED frame observed, room and status SUBSCRIBE receipts required";
  }
  if (options.requireRoomReceipts) {
    return "CONNECTED frame observed, room-topic SUBSCRIBE receipt required; status queue receipts diagnostic only";
  }
  if (options.requireStatusReceipts) {
    return "CONNECTED frame observed, status queue SUBSCRIBE receipts required; room-topic receipt diagnostic only";
  }
  return "CONNECTED frame observed; room and status SUBSCRIBE receipts diagnostic only";
}

function stompFrame(command, headers, body = "") {
  const lines = [command];
  for (const [key, value] of Object.entries(headers)) {
    lines.push(`${key}:${value}`);
  }
  return `${lines.join("\n")}\n\n${body}\0`;
}

function splitFrames(message) {
  return String(message)
    .split("\0")
    .map((frame) => frame.trim())
    .filter(Boolean);
}

function parseFrame(rawFrame) {
  const separator = rawFrame.indexOf("\n\n");
  const headerPart = separator >= 0 ? rawFrame.slice(0, separator) : rawFrame;
  const body = separator >= 0 ? rawFrame.slice(separator + 2) : "";
  const [command, ...headerLines] = headerPart.split("\n");
  const headers = {};
  for (const line of headerLines) {
    const colon = line.indexOf(":");
    if (colon > 0) {
      headers[line.slice(0, colon)] = line.slice(colon + 1);
    }
  }
  return { command, headers, body };
}

function sequenceFromContent(content) {
  const value = Number(String(content ?? "").split(":")[1]);
  return Number.isFinite(value) ? value : undefined;
}

function runIdFromContent(content) {
  return String(content ?? "").split(":")[0] || undefined;
}

function safeJson(text) {
  try {
    return JSON.parse(text || "{}");
  } catch {
    return null;
  }
}

function normalizePublishStatus(status) {
  switch (String(status ?? "").toUpperCase()) {
    case "ACCEPTED":
      return "accepted";
    case "FAILED":
      return "failed";
    case "PERSISTED":
      return "persisted";
    default:
      return null;
  }
}

function writeJsonLines(outDir, filename, rows) {
  mkdirSync(outDir, { recursive: true });
  writeFileSync(
    join(outDir, filename),
    rows.map((row) => JSON.stringify(row)).join("\n") + "\n",
    "utf8",
  );
}

function writeJson(outDir, filename, value) {
  mkdirSync(outDir, { recursive: true });
  writeFileSync(join(outDir, filename), `${JSON.stringify(value, null, 2)}\n`, "utf8");
}

function summarizeDelivery(outDir) {
  const summaryPath = join(outDir, "summary.json");
  const output = execFileSync(
    process.execPath,
    [
      "scripts/delivery-matrix.mjs",
      "--members",
      join(outDir, "members.jsonl"),
      "--send",
      join(outDir, "send.jsonl"),
      "--receive",
      join(outDir, "receive.jsonl"),
      "--status",
      join(outDir, "status.jsonl"),
      "--http",
      join(outDir, "http.jsonl"),
      "--json-out",
      summaryPath,
    ],
    { encoding: "utf8" },
  );
  return JSON.parse(output);
}

function sleep(ms) {
  return new Promise((resolve) => setTimeout(resolve, ms));
}

main().catch((error) => {
  console.error(error);
  process.exit(1);
});
