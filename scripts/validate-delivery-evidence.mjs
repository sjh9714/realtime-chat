#!/usr/bin/env node

import { execFileSync } from "node:child_process";
import { existsSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));

function usage(stream = console.error) {
  stream(
    [
      "Usage:",
      "  node scripts/validate-delivery-evidence.mjs --artifact-dir artifacts/ws/<run-id>",
      "",
      "Validates that a WebSocket delivery evidence artifact contains:",
      "  manifest.json, summary.json, and raw JSONL files needed to regenerate summary.json",
      "  manifest options/environment/claim boundary",
      "  expected sessions/rooms/messages aligned with raw logs and summary.byRoom",
      "  mixedHttp.failedRequests === 0 when mixed HTTP probes are included",
      "",
      "This validator accepts scenario-validation diagnostics such as missing, duplicate,",
      "unexpected, failed, or statusless sends. It does not promote artifacts to benchmark claims.",
    ].join("\n"),
  );
}

function parseArgs(argv) {
  if (argv.length === 1 && (argv[0] === "--help" || argv[0] === "-h")) {
    usage(console.log);
    process.exit(0);
  }

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
  if (!args.has("artifact-dir")) {
    usage();
    process.exit(2);
  }
  return { artifactDir: resolve(args.get("artifact-dir")) };
}

function main() {
  const { artifactDir } = parseArgs(process.argv.slice(2));
  const errors = validateArtifact(artifactDir);
  if (errors.length > 0) {
    for (const error of errors) {
      console.error(`- ${error}`);
    }
    process.exit(1);
  }
  console.log(`delivery evidence validation passed: ${artifactDir}`);
}

function validateArtifact(artifactDir) {
  const errors = [];
  const manifestPath = join(artifactDir, "manifest.json");
  const summaryPath = join(artifactDir, "summary.json");

  if (!existsSync(manifestPath)) {
    errors.push("manifest.json is required");
  }
  if (!existsSync(summaryPath)) {
    errors.push("summary.json is required");
  }
  if (errors.length > 0) {
    return errors;
  }

  const manifest = readJson(manifestPath, errors);
  const summary = readJson(summaryPath, errors);
  if (!manifest || !summary) {
    return errors;
  }

  validateManifestBoundary(manifest, errors);

  const files = {
    members: manifest.files?.members ?? "members.jsonl",
    send: manifest.files?.send ?? "send.jsonl",
    receive: manifest.files?.receive ?? "receive.jsonl",
    status: manifest.files?.status ?? "status.jsonl",
    http: manifest.files?.http ?? "http.jsonl",
  };

  const requiredFiles = ["members", "send", "receive"];
  if (manifest.files?.status || existsSync(join(artifactDir, files.status))) {
    requiredFiles.push("status");
  }
  if (
    manifest.files?.http ||
    manifest.expected?.mixedHttpProbesIncluded === true ||
    manifest.options?.mixedHttpProbes === true ||
    existsSync(join(artifactDir, files.http))
  ) {
    requiredFiles.push("http");
  }

  for (const fileKey of requiredFiles) {
    const filePath = join(artifactDir, files[fileKey]);
    if (!existsSync(filePath)) {
      errors.push(`${files[fileKey]} is required to regenerate summary.json`);
    }
  }
  if (errors.length > 0) {
    return errors;
  }

  const rows = {
    members: readJsonLines(join(artifactDir, files.members), errors),
    send: readJsonLines(join(artifactDir, files.send), errors),
    receive: readJsonLines(join(artifactDir, files.receive), errors),
    status: existsSync(join(artifactDir, files.status))
      ? readJsonLines(join(artifactDir, files.status), errors)
      : [],
    http: existsSync(join(artifactDir, files.http))
      ? readJsonLines(join(artifactDir, files.http), errors)
      : [],
  };
  if (errors.length > 0) {
    return errors;
  }

  validateExpectedBoundary(manifest, summary, rows, errors);
  validateRegeneratedSummary(artifactDir, files, summary, errors);

  if (
    (manifest.expected?.mixedHttpProbesIncluded === true ||
      manifest.options?.mixedHttpProbes === true) &&
    summary.mixedHttp?.failedRequests !== 0
  ) {
    errors.push(
      `mixed HTTP probes are included but mixedHttp.failedRequests=${summary.mixedHttp?.failedRequests}`,
    );
  }

  return errors;
}

function validateManifestBoundary(manifest, errors) {
  if (!manifest.options || typeof manifest.options !== "object") {
    errors.push("manifest.json must record options");
  }
  if (!manifest.environment || typeof manifest.environment !== "object") {
    errors.push("manifest.json must record environment");
  }
  if (!manifest.claimBoundary || typeof manifest.claimBoundary !== "object") {
    errors.push("manifest.json must record claimBoundary");
  }
  if (
    manifest.claimBoundary?.status &&
    manifest.claimBoundary.status !== "시나리오 검증"
  ) {
    errors.push(
      `manifest claimBoundary.status must stay 시나리오 검증, got ${manifest.claimBoundary.status}`,
    );
  }
}

function validateExpectedBoundary(manifest, summary, rows, errors) {
  const roomIds = [...new Set(rows.members.map((row) => row.roomId))].sort();
  const userIds = [...new Set(rows.members.map((row) => row.userId))];
  const expected = manifest.expected ?? {};
  const options = manifest.options ?? {};

  compareNumber(
    expected.sessions ?? options.users,
    userIds.length,
    "expected sessions",
    errors,
  );
  compareNumber(expected.rooms ?? options.rooms, roomIds.length, "expected rooms", errors);
  compareNumber(
    expected.messagesAttempted,
    rows.send.length,
    "expected messagesAttempted",
    errors,
  );

  const expectedMessages =
    Number(expected.rooms) *
    Number(expected.sendersPerRoom) *
    Number(expected.messagesPerUser);
  if (Number.isFinite(expectedMessages)) {
    compareNumber(
      expected.messagesAttempted,
      expectedMessages,
      "expected room/sender/message product",
      errors,
    );
  }

  const manifestRoomIds = [...(expected.roomIds ?? [])].sort();
  if (
    manifestRoomIds.length > 0 &&
    JSON.stringify(manifestRoomIds) !== JSON.stringify(roomIds)
  ) {
    errors.push(
      `manifest roomIds do not match members.jsonl: manifest=${manifestRoomIds.join(",")} raw=${roomIds.join(",")}`,
    );
  }

  for (const roomId of roomIds) {
    if (!summary.byRoom?.[roomId]) {
      errors.push(`summary.byRoom is missing roomId=${roomId}`);
    }
  }

  const aggregateFields = [
    "expectedDeliveries",
    "actualUniqueDeliveries",
    "missingDeliveries",
    "duplicateDeliveries",
    "unexpectedDeliveries",
  ];
  for (const field of aggregateFields) {
    if (!Number.isFinite(Number(summary[field]))) {
      errors.push(`summary.${field} must be numeric`);
    }
  }
  for (const field of ["totalSends", "failedSends", "statuslessSends"]) {
    if (!Number.isFinite(Number(summary.sendStatus?.[field]))) {
      errors.push(`summary.sendStatus.${field} must be numeric`);
    }
  }
}

function validateRegeneratedSummary(artifactDir, files, summary, errors) {
  const args = [
    join("scripts", "delivery-matrix.mjs"),
    "--members",
    join(artifactDir, files.members),
    "--send",
    join(artifactDir, files.send),
    "--receive",
    join(artifactDir, files.receive),
  ];
  if (existsSync(join(artifactDir, files.status))) {
    args.push("--status", join(artifactDir, files.status));
  }
  if (existsSync(join(artifactDir, files.http))) {
    args.push("--http", join(artifactDir, files.http));
  }

  let regenerated;
  try {
    regenerated = JSON.parse(
      execFileSync(process.execPath, args, {
        cwd: resolve(join(scriptDir, "..")),
        encoding: "utf8",
      }),
    );
  } catch (error) {
    errors.push(`summary regeneration failed: ${error.message}`);
    return;
  }

  const comparableFields = [
    "expectedDeliveries",
    "actualUniqueDeliveries",
    "missingDeliveries",
    "duplicateDeliveries",
    "unexpectedDeliveries",
    "senderLocalOutOfOrderCount",
    "roomGlobalOutOfOrderCount",
    "roomGlobalOrdering",
    "completenessPercent",
    "sendStatus",
    "acceptedDelivery",
    "persistedDelivery",
    "latencyMs",
    "mixedHttp",
    "byRoom",
  ];
  for (const field of comparableFields) {
    if (JSON.stringify(summary[field]) !== JSON.stringify(regenerated[field])) {
      errors.push(`summary.${field} does not match regenerated delivery-matrix output`);
    }
  }
}

function compareNumber(actual, expected, label, errors) {
  if (actual === undefined || actual === null) {
    errors.push(`manifest ${label} is required`);
    return;
  }
  if (Number(actual) !== Number(expected)) {
    errors.push(`manifest ${label} expected ${expected}, got ${actual}`);
  }
}

function readJson(path, errors) {
  try {
    return JSON.parse(readFileSync(path, "utf8"));
  } catch (error) {
    errors.push(`${path} is not valid JSON: ${error.message}`);
    return null;
  }
}

function readJsonLines(path, errors) {
  const text = readFileSync(path, "utf8").trim();
  if (!text) {
    return [];
  }
  return text
    .split(/\r?\n/)
    .map((line, index) => {
      try {
        return JSON.parse(line);
      } catch (error) {
        errors.push(`${path}:${index + 1} is not valid JSON: ${error.message}`);
        return null;
      }
    })
    .filter(Boolean);
}

main();
