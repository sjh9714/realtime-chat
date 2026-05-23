#!/usr/bin/env node

import { mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { dirname } from "node:path";

function usage() {
  console.error(
    [
      "Usage:",
      "  node scripts/delivery-matrix.mjs --members members.jsonl --send send.jsonl --receive receive.jsonl [--status status.jsonl] [--http http.jsonl] [--json-out summary.json]",
      "",
      "Each JSONL line must contain:",
      "  members: { roomId, userId }",
      "  send: { roomId, senderUserId, clientMessageId, sendStartedAtMs, roomSequence? }",
      "  receive: { roomId, receiverUserId, senderUserId, clientMessageId, receivedAtMs, roomSequence? }",
      "  status: { clientMessageId?, userId, roomId?, status, observedAtMs?, reason? }",
      "  http: { operation, method, path, status, ok, durationMs }",
    ].join("\n"),
  );
}

function parseArgs(argv) {
  const args = new Map();
  for (let index = 0; index < argv.length; index += 2) {
    const key = argv[index];
    const value = argv[index + 1];
    if (!key?.startsWith("--") || !value) {
      usage();
      process.exit(2);
    }
    args.set(key.slice(2), value);
  }
  for (const key of ["members", "send", "receive"]) {
    if (!args.has(key)) {
      usage();
      process.exit(2);
    }
  }
  return args;
}

function readJsonLines(path) {
  const text = readFileSync(path, "utf8").trim();
  if (!text) {
    return [];
  }
  return text.split(/\r?\n/).map((line, index) => {
    try {
      return JSON.parse(line);
    } catch (error) {
      throw new Error(`${path}:${index + 1} is not valid JSON: ${error.message}`);
    }
  });
}

function keyOfMessage(roomId, clientMessageId) {
  return `${roomId}\u0000${clientMessageId}`;
}

function keyOfDelivery(roomId, receiverUserId, clientMessageId) {
  return `${roomId}\u0000${receiverUserId}\u0000${clientMessageId}`;
}

function normalizeStatus(status) {
  switch (String(status ?? "").toUpperCase()) {
    case "ACCEPTED":
      return "accepted";
    case "PERSISTED":
      return "persisted";
    case "FAILED":
      return "failed";
    case "RATE_LIMITED":
      return "rate_limited";
    case "STOMP_ERROR":
      return "stomp_error";
    default:
      return String(status ?? "").toLowerCase();
  }
}

function numericMessageId(value) {
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : null;
}

function percentile(values, ratio) {
  if (values.length === 0) {
    return null;
  }
  const sorted = [...values].sort((left, right) => left - right);
  const index = Math.ceil(ratio * sorted.length) - 1;
  return sorted[Math.max(0, Math.min(index, sorted.length - 1))];
}

function summarize({ members, sends, receives, statuses = [], httpRows = [] }) {
  const roomMembers = new Map();
  for (const member of members) {
    if (!member.roomId || !member.userId) {
      throw new Error("member rows require roomId and userId");
    }
    if (!roomMembers.has(member.roomId)) {
      roomMembers.set(member.roomId, new Set());
    }
    roomMembers.get(member.roomId).add(member.userId);
  }

  const aggregate = summarizeScope({ roomMembers, sends, receives, statuses });
  const byRoom = {};
  for (const roomId of [...roomMembers.keys()].sort()) {
    byRoom[roomId] = summarizeScope({
      roomMembers: new Map([[roomId, roomMembers.get(roomId)]]),
      sends: sends.filter((send) => send.roomId === roomId),
      receives: receives.filter((receive) => receive.roomId === roomId),
      statuses: statuses.filter((status) => status.roomId === roomId),
    });
  }

  return { ...aggregate, byRoom, mixedHttp: summarizeHttp(httpRows) };
}

function summarizeHttp(rows) {
  const byOperation = {};
  const okRequests = rows.filter((row) => row.ok === true).length;
  const failedRequests = rows.length - okRequests;
  for (const row of rows) {
    const operation = row.operation ?? "unknown";
    if (!byOperation[operation]) {
      byOperation[operation] = [];
    }
    byOperation[operation].push(row);
  }

  return {
    totalRequests: rows.length,
    okRequests,
    failedRequests,
    byOperation: Object.fromEntries(
      Object.entries(byOperation)
        .sort(([left], [right]) => left.localeCompare(right))
        .map(([operation, operationRows]) => [
          operation,
          summarizeHttpOperation(operationRows),
        ]),
    ),
  };
}

function summarizeHttpOperation(rows) {
  const latencyMs = rows
    .map((row) => Number(row.durationMs))
    .filter(Number.isFinite);
  const okRequests = rows.filter((row) => row.ok === true).length;
  return {
    totalRequests: rows.length,
    okRequests,
    failedRequests: rows.length - okRequests,
    latencyMs: {
      count: latencyMs.length,
      p50: percentile(latencyMs, 0.5),
      p90: percentile(latencyMs, 0.9),
      p95: percentile(latencyMs, 0.95),
      p99: percentile(latencyMs, 0.99),
      max: latencyMs.length ? Math.max(...latencyMs) : null,
    },
  };
}

function summarizeScope({ roomMembers, sends, receives, statuses = [] }) {
  const statusIndex = indexStatuses(statuses);
  const sendsByMessageKey = new Map();
  for (const send of sends) {
    if (!send.roomId || !send.senderUserId || !send.clientMessageId) {
      throw new Error("send rows require roomId, senderUserId, and clientMessageId");
    }
    sendsByMessageKey.set(keyOfMessage(send.roomId, send.clientMessageId), send);
  }

  const allExpected = buildExpectedSet(sends, roomMembers);
  const acceptedSends = sends.filter((send) =>
    hasAnyStatus(statusesForSend(statusIndex, send), ["accepted", "persisted"]),
  );
  const persistedSends = sends.filter((send) =>
    hasAnyStatus(statusesForSend(statusIndex, send), ["persisted"]),
  );
  const failedSends = sends.filter((send) =>
    hasAnyStatus(statusesForSend(statusIndex, send), ["failed"]),
  );
  const rateLimitedSends = sends.filter((send) =>
    hasAnyStatus(statusesForSend(statusIndex, send), ["rate_limited"]),
  );
  const classifiedSendKeys = new Set([
    ...acceptedSends.map((send) => keyOfMessage(send.roomId, send.clientMessageId)),
    ...failedSends.map((send) => keyOfMessage(send.roomId, send.clientMessageId)),
    ...rateLimitedSends.map((send) => keyOfMessage(send.roomId, send.clientMessageId)),
  ]);
  const statuslessSends = sends.filter(
    (send) => !classifiedSendKeys.has(keyOfMessage(send.roomId, send.clientMessageId)),
  );

  const acceptedExpected = buildExpectedSet(acceptedSends, roomMembers);
  const persistedExpected = buildExpectedSet(persistedSends, roomMembers);

  const actual = new Set();
  let duplicateDeliveries = 0;
  let unexpectedDeliveries = 0;
  const latencyMs = [];
  const lastSequenceByReceiverRoomSender = new Map();
  const lastPersistedMessageIdByReceiverRoom = new Map();
  let senderLocalOutOfOrderCount = 0;
  let roomGlobalOutOfOrderCount = 0;
  let roomGlobalComparableDeliveries = 0;

  for (const receive of receives) {
    if (!receive.roomId || !receive.receiverUserId || !receive.clientMessageId) {
      throw new Error("receive rows require roomId, receiverUserId, and clientMessageId");
    }
    const send = sendsByMessageKey.get(
      keyOfMessage(receive.roomId, receive.clientMessageId),
    );
    const deliveryKey = keyOfDelivery(
      receive.roomId,
      receive.receiverUserId,
      receive.clientMessageId,
    );
    if (!allExpected.has(deliveryKey)) {
      unexpectedDeliveries += 1;
      continue;
    }
    if (actual.has(deliveryKey)) {
      duplicateDeliveries += 1;
    } else {
      actual.add(deliveryKey);
    }
    if (Number.isFinite(send?.sendStartedAtMs) && Number.isFinite(receive.receivedAtMs)) {
      latencyMs.push(receive.receivedAtMs - send.sendStartedAtMs);
    }
    const persistedMessageId = resolvePersistedMessageId(statusIndex, receive, send);
    if (persistedMessageId !== null) {
      roomGlobalComparableDeliveries += 1;
      const orderKey = `${receive.receiverUserId}\u0000${receive.roomId}`;
      const previousMessageId = lastPersistedMessageIdByReceiverRoom.get(orderKey);
      if (previousMessageId !== undefined && persistedMessageId < previousMessageId) {
        roomGlobalOutOfOrderCount += 1;
      }
      lastPersistedMessageIdByReceiverRoom.set(
        orderKey,
        Math.max(previousMessageId ?? persistedMessageId, persistedMessageId),
      );
    }
    if (receive.roomSequence !== undefined && receive.roomId) {
      const sequenceKey = `${receive.receiverUserId}\u0000${receive.roomId}\u0000${receive.senderUserId}`;
      const previousSequence = lastSequenceByReceiverRoomSender.get(sequenceKey);
      if (
        previousSequence !== undefined &&
        Number(receive.roomSequence) < previousSequence
      ) {
        senderLocalOutOfOrderCount += 1;
      }
      lastSequenceByReceiverRoomSender.set(
        sequenceKey,
        Number(receive.roomSequence),
      );
    }
  }

  const allDelivery = summarizeExpectedSet(allExpected, actual);
  const acceptedDelivery = summarizeExpectedSet(acceptedExpected, actual);
  const persistedDelivery = summarizeExpectedSet(persistedExpected, actual);

  return {
    expectedDeliveries: allDelivery.expectedDeliveries,
    actualUniqueDeliveries: allDelivery.actualUniqueDeliveries,
    missingDeliveries: allDelivery.missingDeliveries,
    duplicateDeliveries,
    unexpectedDeliveries,
    senderLocalOutOfOrderCount,
    roomGlobalOutOfOrderCount,
    roomGlobalOrdering: {
      source:
        roomGlobalComparableDeliveries > 0 ? "persistedMessageId" : "unavailable",
      comparableDeliveries: roomGlobalComparableDeliveries,
      outOfOrderCount: roomGlobalOutOfOrderCount,
    },
    completenessPercent: allDelivery.completenessPercent,
    sendStatus: {
      totalSends: sends.length,
      statusesObserved: statuses.length,
      acceptedSends: acceptedSends.length,
      persistedSends: persistedSends.length,
      failedSends: failedSends.length,
      rateLimitedSends: rateLimitedSends.length,
      statuslessSends: statuslessSends.length,
      stompErrorsWithoutClientMessageId: statusIndex.stompErrorsWithoutClientMessageId,
    },
    acceptedDelivery,
    persistedDelivery,
    latencyMs: {
      count: latencyMs.length,
      p50: percentile(latencyMs, 0.5),
      p90: percentile(latencyMs, 0.9),
      p95: percentile(latencyMs, 0.95),
      p99: percentile(latencyMs, 0.99),
      max: latencyMs.length ? Math.max(...latencyMs) : null,
    },
  };
}

function buildExpectedSet(sends, roomMembers) {
  const expected = new Set();
  for (const send of sends) {
    const membersInRoom = roomMembers.get(send.roomId);
    if (!membersInRoom) {
      throw new Error(`missing member matrix for roomId=${send.roomId}`);
    }
    for (const receiverUserId of membersInRoom) {
      if (receiverUserId === send.senderUserId) {
        continue;
      }
      expected.add(keyOfDelivery(send.roomId, receiverUserId, send.clientMessageId));
    }
  }
  return expected;
}

function summarizeExpectedSet(expected, actual) {
  let actualUniqueDeliveries = 0;
  for (const deliveryKey of actual) {
    if (expected.has(deliveryKey)) {
      actualUniqueDeliveries += 1;
    }
  }
  const expectedDeliveries = expected.size;
  const missingDeliveries = expectedDeliveries - actualUniqueDeliveries;
  const completenessPercent =
    expectedDeliveries === 0
      ? null
      : Number(((actualUniqueDeliveries / expectedDeliveries) * 100).toFixed(4));

  return {
    expectedDeliveries,
    actualUniqueDeliveries,
    missingDeliveries,
    completenessPercent,
  };
}

function indexStatuses(statuses) {
  const byMessageKey = new Map();
  const messageIdsByMessageKey = new Map();
  let stompErrorsWithoutClientMessageId = 0;
  for (const statusRow of statuses) {
    const status = normalizeStatus(statusRow.status);
    if (!statusRow.clientMessageId) {
      if (status === "stomp_error") {
        stompErrorsWithoutClientMessageId += 1;
      }
      continue;
    }
    const statusKey = statusRow.roomId
      ? keyOfMessage(statusRow.roomId, statusRow.clientMessageId)
      : statusRow.clientMessageId;
    if (!byMessageKey.has(statusKey)) {
      byMessageKey.set(statusKey, new Set());
    }
    byMessageKey.get(statusKey).add(status);
    const messageId = numericMessageId(statusRow.messageId);
    if (messageId !== null) {
      messageIdsByMessageKey.set(statusKey, messageId);
    }
  }
  return { byMessageKey, messageIdsByMessageKey, stompErrorsWithoutClientMessageId };
}

function statusesForSend(statusIndex, send) {
  return (
    statusIndex.byMessageKey.get(keyOfMessage(send.roomId, send.clientMessageId)) ??
    statusIndex.byMessageKey.get(send.clientMessageId)
  );
}

function hasAnyStatus(statuses, candidates) {
  if (!statuses) {
    return false;
  }
  return candidates.some((candidate) => statuses.has(candidate));
}

function resolvePersistedMessageId(statusIndex, receive, send) {
  for (const candidate of [
    receive.messageId,
    receive.persistedMessageId,
    send?.messageId,
    send?.persistedMessageId,
    statusIndex.messageIdsByMessageKey.get(
      keyOfMessage(receive.roomId, receive.clientMessageId),
    ),
    statusIndex.messageIdsByMessageKey.get(receive.clientMessageId),
  ]) {
    const messageId = numericMessageId(candidate);
    if (messageId !== null) {
      return messageId;
    }
  }
  return null;
}

const args = parseArgs(process.argv.slice(2));
const summary = summarize({
  members: readJsonLines(args.get("members")),
  sends: readJsonLines(args.get("send")),
  receives: readJsonLines(args.get("receive")),
  statuses: args.has("status") ? readJsonLines(args.get("status")) : [],
  httpRows: args.has("http") ? readJsonLines(args.get("http")) : [],
});

const output = JSON.stringify(summary, null, 2);
if (args.has("json-out")) {
  mkdirSync(dirname(args.get("json-out")), { recursive: true });
  writeFileSync(args.get("json-out"), `${output}\n`, "utf8");
}

console.log(output);
