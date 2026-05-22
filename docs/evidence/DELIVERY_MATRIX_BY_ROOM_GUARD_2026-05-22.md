# Delivery Matrix By-Room Guard

Date: 2026-05-22

This is a deterministic fixture test for the receiver-matrix calculator, not a public
WebSocket performance benchmark.

## Command

```bash
node scripts/delivery-matrix-smoke-test.mjs
```

## What It Guards

- Aggregate delivery denominator remains available for existing evidence.
- `summary.byRoom` splits expected, actual, missing, duplicate, unexpected, accepted, persisted, and latency fields by `roomId`.
- Delivery identity includes `roomId`, `receiverUserId`, and `clientMessageId`.
- A cross-room receive row is counted as `unexpectedDeliveries` for the wrong room instead of being silently absorbed by aggregate totals.

## Claim Boundary

This guard only proves that the evidence tooling can separate room-level denominators.
It does not prove 10-room, 50-user-per-room, 1,000-session, mixed-traffic, or production
WebSocket delivery quality.
