#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const workDir = mkdtempSync(join(tmpdir(), "delivery-matrix-smoke-"));

writeJsonLines("members.jsonl", [
  { roomId: "room-1", userId: "sender" },
  { roomId: "room-1", userId: "receiver-1" },
  { roomId: "room-1", userId: "receiver-2" },
  { roomId: "room-2", userId: "sender-2" },
  { roomId: "room-2", userId: "receiver-3" },
]);
writeJsonLines("send.jsonl", [
  {
    roomId: "room-1",
    senderUserId: "sender",
    clientMessageId: "accepted-message",
    sendStartedAtMs: 1000,
  },
  {
    roomId: "room-1",
    senderUserId: "sender",
    clientMessageId: "failed-message",
    sendStartedAtMs: 1000,
  },
  {
    roomId: "room-1",
    senderUserId: "sender",
    clientMessageId: "persisted-only-message",
    sendStartedAtMs: 1000,
  },
  {
    roomId: "room-1",
    senderUserId: "sender",
    clientMessageId: "statusless-message",
    sendStartedAtMs: 1000,
  },
  {
    roomId: "room-2",
    senderUserId: "sender-2",
    clientMessageId: "accepted-message-room-2",
    sendStartedAtMs: 1000,
  },
]);
writeJsonLines("receive.jsonl", [
  {
    roomId: "room-1",
    receiverUserId: "receiver-1",
    senderUserId: "sender",
    clientMessageId: "accepted-message",
    receivedAtMs: 1020,
  },
  {
    roomId: "room-1",
    receiverUserId: "receiver-2",
    senderUserId: "sender",
    clientMessageId: "accepted-message",
    receivedAtMs: 1030,
  },
  {
    roomId: "room-1",
    receiverUserId: "receiver-1",
    senderUserId: "sender",
    clientMessageId: "persisted-only-message",
    receivedAtMs: 1040,
  },
  {
    roomId: "room-1",
    receiverUserId: "receiver-2",
    senderUserId: "sender",
    clientMessageId: "persisted-only-message",
    receivedAtMs: 1050,
  },
  {
    roomId: "room-1",
    receiverUserId: "receiver-1",
    senderUserId: "sender",
    clientMessageId: "statusless-message",
    receivedAtMs: 1060,
  },
  {
    roomId: "room-2",
    receiverUserId: "receiver-3",
    senderUserId: "sender-2",
    clientMessageId: "accepted-message-room-2",
    receivedAtMs: 1070,
  },
  {
    roomId: "room-2",
    receiverUserId: "receiver-3",
    senderUserId: "sender",
    clientMessageId: "accepted-message",
    receivedAtMs: 1080,
  },
]);
writeJsonLines("status.jsonl", [
  {
    roomId: "room-1",
    userId: "sender",
    clientMessageId: "accepted-message",
    status: "accepted",
  },
  {
    roomId: "room-1",
    userId: "sender",
    clientMessageId: "persisted-only-message",
    status: "persisted",
  },
  {
    roomId: "room-1",
    userId: "sender",
    clientMessageId: "failed-message",
    status: "failed",
  },
  {
    roomId: "room-1",
    userId: "sender",
    status: "stomp_error",
    reason: "메시지 전송 속도 제한을 초과했습니다.",
  },
  {
    roomId: "room-2",
    userId: "sender-2",
    clientMessageId: "accepted-message-room-2",
    status: "accepted",
  },
]);

const summary = runDeliveryMatrix();

assertEqual(summary.expectedDeliveries, 9, "total expected deliveries");
assertEqual(summary.actualUniqueDeliveries, 6, "total unique deliveries");
assertEqual(summary.unexpectedDeliveries, 1, "unexpected deliveries");
assertEqual(summary.missingDeliveries, 3, "total missing deliveries");
assertEqual(summary.sendStatus.totalSends, 5, "total sends");
assertEqual(summary.sendStatus.acceptedSends, 3, "accepted sends");
assertEqual(summary.sendStatus.persistedSends, 1, "persisted sends");
assertEqual(summary.sendStatus.failedSends, 1, "failed sends");
assertEqual(summary.sendStatus.statuslessSends, 1, "statusless sends");
assertEqual(
  summary.sendStatus.stompErrorsWithoutClientMessageId,
  1,
  "STOMP errors without clientMessageId",
);
assertEqual(summary.acceptedDelivery.expectedDeliveries, 5, "accepted expected");
assertEqual(summary.acceptedDelivery.actualUniqueDeliveries, 5, "accepted actual");
assertEqual(summary.acceptedDelivery.completenessPercent, 100, "accepted percent");
assertEqual(summary.persistedDelivery.expectedDeliveries, 2, "persisted expected");
assertEqual(summary.persistedDelivery.actualUniqueDeliveries, 2, "persisted actual");
assertEqual(summary.persistedDelivery.completenessPercent, 100, "persisted percent");
assertEqual(summary.byRoom["room-1"].expectedDeliveries, 8, "room-1 expected");
assertEqual(summary.byRoom["room-1"].actualUniqueDeliveries, 5, "room-1 actual");
assertEqual(summary.byRoom["room-1"].unexpectedDeliveries, 0, "room-1 unexpected");
assertEqual(summary.byRoom["room-1"].missingDeliveries, 3, "room-1 missing");
assertEqual(summary.byRoom["room-2"].expectedDeliveries, 1, "room-2 expected");
assertEqual(summary.byRoom["room-2"].actualUniqueDeliveries, 1, "room-2 actual");
assertEqual(summary.byRoom["room-2"].unexpectedDeliveries, 1, "room-2 unexpected");
assertEqual(summary.byRoom["room-2"].missingDeliveries, 0, "room-2 missing");
assertEqual(summary.mixedHttp.totalRequests, 0, "no HTTP probes by default");

writeJsonLines("http.jsonl", [
  {
    runId: "smoke",
    roomId: "room-1",
    userId: "receiver-1",
    operation: "rooms_list",
    method: "GET",
    path: "/api/rooms",
    status: 200,
    ok: true,
    durationMs: 10,
  },
  {
    runId: "smoke",
    roomId: "room-1",
    userId: "receiver-1",
    operation: "message_history",
    method: "GET",
    path: "/api/rooms/room-1/messages?size=20",
    status: 200,
    ok: true,
    durationMs: 30,
    latestMessageId: 77,
  },
  {
    runId: "smoke",
    roomId: "room-1",
    userId: "receiver-1",
    operation: "read_receipt",
    method: "POST",
    path: "/api/rooms/room-1/read",
    status: 200,
    ok: true,
    durationMs: 20,
    latestMessageId: 77,
  },
  {
    runId: "smoke",
    roomId: "room-2",
    userId: "receiver-3",
    operation: "message_history",
    method: "GET",
    path: "/api/rooms/room-2/messages?size=20",
    status: 500,
    ok: false,
    durationMs: 40,
    error: "synthetic failure",
  },
]);

const summaryWithHttp = runDeliveryMatrix(["--http", join(workDir, "http.jsonl")]);
assertEqual(
  summaryWithHttp.expectedDeliveries,
  summary.expectedDeliveries,
  "HTTP probes do not alter delivery denominator",
);
assertEqual(
  summaryWithHttp.actualUniqueDeliveries,
  summary.actualUniqueDeliveries,
  "HTTP probes do not alter actual deliveries",
);
assertEqual(summaryWithHttp.mixedHttp.totalRequests, 4, "HTTP total requests");
assertEqual(summaryWithHttp.mixedHttp.okRequests, 3, "HTTP ok requests");
assertEqual(summaryWithHttp.mixedHttp.failedRequests, 1, "HTTP failed requests");
assertEqual(
  summaryWithHttp.mixedHttp.byOperation.rooms_list.totalRequests,
  1,
  "rooms list HTTP count",
);
assertEqual(
  summaryWithHttp.mixedHttp.byOperation.message_history.totalRequests,
  2,
  "message history HTTP count",
);
assertEqual(
  summaryWithHttp.mixedHttp.byOperation.message_history.failedRequests,
  1,
  "message history failed HTTP count",
);
assertEqual(
  summaryWithHttp.mixedHttp.byOperation.message_history.latencyMs.p95,
  40,
  "message history p95",
);
assertEqual(
  summaryWithHttp.mixedHttp.byOperation.read_receipt.latencyMs.p95,
  20,
  "read receipt p95",
);

console.log("delivery-matrix smoke test passed");

function runDeliveryMatrix(extraArgs = []) {
  const output = execFileSync(
    process.execPath,
    [
      "scripts/delivery-matrix.mjs",
      "--members",
      join(workDir, "members.jsonl"),
      "--send",
      join(workDir, "send.jsonl"),
      "--receive",
      join(workDir, "receive.jsonl"),
      "--status",
      join(workDir, "status.jsonl"),
      ...extraArgs,
    ],
    { encoding: "utf8" },
  );
  return JSON.parse(output);
}

function writeJsonLines(filename, rows) {
  writeFileSync(
    join(workDir, filename),
    `${rows.map((row) => JSON.stringify(row)).join("\n")}\n`,
    "utf8",
  );
}

function assertEqual(actual, expected, label) {
  if (actual !== expected) {
    throw new Error(`${label}: expected ${expected}, got ${actual}`);
  }
}
