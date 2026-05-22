#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { mkdtempSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";

const workDir = mkdtempSync(join(tmpdir(), "delivery-evidence-validator-smoke-"));

writeJsonLines("members.jsonl", [
  { runId: "smoke", roomId: "room-1", userId: "sender" },
  { runId: "smoke", roomId: "room-1", userId: "receiver-1" },
  { runId: "smoke", roomId: "room-1", userId: "receiver-2" },
]);
writeJsonLines("send.jsonl", [
  {
    runId: "smoke",
    roomId: "room-1",
    senderUserId: "sender",
    clientMessageId: "message-1",
    sendStartedAtMs: 1000,
  },
]);
writeJsonLines("receive.jsonl", [
  {
    runId: "smoke",
    roomId: "room-1",
    receiverUserId: "receiver-1",
    senderUserId: "sender",
    clientMessageId: "message-1",
    receivedAtMs: 1020,
  },
  {
    runId: "smoke",
    roomId: "room-1",
    receiverUserId: "receiver-2",
    senderUserId: "sender",
    clientMessageId: "message-1",
    receivedAtMs: 1030,
  },
]);
writeJsonLines("status.jsonl", [
  {
    runId: "smoke",
    roomId: "room-1",
    userId: "sender",
    clientMessageId: "message-1",
    status: "accepted",
  },
]);
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
]);

const summary = JSON.parse(
  execFileSync(
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
      "--http",
      join(workDir, "http.jsonl"),
    ],
    { encoding: "utf8" },
  ),
);
writeJson(workDir, "summary.json", summary);
writeJson(workDir, "manifest.json", {
  schemaVersion: 1,
  artifactType: "websocket-delivery-evidence",
  claimBoundary: {
    status: "시나리오 검증",
    scope: "validator smoke fixture",
  },
  options: {
    users: 3,
    rooms: 1,
    usersPerRoom: 3,
    messagesPerUser: 1,
    sendersPerRoom: 1,
    mixedHttpProbes: true,
  },
  expected: {
    sessions: 3,
    rooms: 1,
    usersPerRoom: 3,
    sendersPerRoom: 1,
    messagesPerUser: 1,
    messagesAttempted: 1,
    roomIds: ["room-1"],
    mixedHttpProbesIncluded: true,
  },
  environment: {
    nodeVersion: process.version,
    platform: process.platform,
  },
  files: {
    members: "members.jsonl",
    send: "send.jsonl",
    receive: "receive.jsonl",
    status: "status.jsonl",
    http: "http.jsonl",
    summary: "summary.json",
  },
});

execFileSync(
  process.execPath,
  ["scripts/validate-delivery-evidence.mjs", "--artifact-dir", workDir],
  { encoding: "utf8" },
);

const brokenDir = mkdtempSync(join(tmpdir(), "delivery-evidence-validator-broken-"));
writeJson(brokenDir, "manifest.json", {
  claimBoundary: { status: "측정 완료" },
  options: {},
  environment: {},
});
try {
  execFileSync(
    process.execPath,
    ["scripts/validate-delivery-evidence.mjs", "--artifact-dir", brokenDir],
    { encoding: "utf8", stdio: "pipe" },
  );
  throw new Error("validator accepted a broken artifact");
} catch (error) {
  if (error.message === "validator accepted a broken artifact") {
    throw error;
  }
}

console.log("delivery evidence validator smoke test passed");

function writeJsonLines(filename, rows) {
  writeFileSync(
    join(workDir, filename),
    `${rows.map((row) => JSON.stringify(row)).join("\n")}\n`,
    "utf8",
  );
}

function writeJson(directory, filename, value) {
  writeFileSync(
    join(directory, filename),
    `${JSON.stringify(value, null, 2)}\n`,
    "utf8",
  );
}
