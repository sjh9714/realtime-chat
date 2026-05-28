# Delivery Evidence Validator Manifest Smoke - 2026-05-22

이 문서는 `scripts/ws-delivery-runner.mjs`가 생성한 manifest-backed WebSocket receiver matrix artifact를
`scripts/validate-delivery-evidence.mjs`로 검산한 작은 local scenario evidence입니다.

## Claim Boundary

- 상태: 시나리오 검증
- 범위: 2-user, 1-room, 1-message local receiver matrix artifact
- 목적: `manifest.json`, raw JSONL, regenerated `summary.json`, `byRoom` coverage를 validator가 실제로 대조할 수 있는지 확인
- 제외: 500/1,000 session benchmark, mixed traffic p95, room-global ordering, 운영 성능 claim. Claim boundary: local validator evidence only.

## Command

```bash
node scripts/ws-delivery-runner.mjs \
  --base http://localhost:8081 \
  --ws ws://localhost:8081/ws,ws://localhost:8082/ws \
  --users 2 \
  --senders 1 \
  --messages 1 \
  --send-interval-ms 125 \
  --status-subscribe-settle-ms 500 \
  --drain-ms 5000 \
  --out-dir artifacts/ws/20260522T153016Z-receiver-matrix-2user-scenario

node scripts/validate-delivery-evidence.mjs \
  --artifact-dir artifacts/ws/20260522T153016Z-receiver-matrix-2user-scenario
```

## Artifact Files

Raw files are local artifacts under `artifacts/ws/20260522T153016Z-receiver-matrix-2user-scenario/` and are not committed.

```text
members.jsonl
send.jsonl
receive.jsonl
status.jsonl
http.jsonl
summary.json
manifest.json
```

## Summary

| Field | Value |
| --- | --- |
| sessions | 2 |
| rooms | 1 |
| messages attempted | 1 |
| expected deliveries | 1 |
| actual unique deliveries | 1 |
| missing deliveries | 0 |
| duplicate deliveries | 0 |
| unexpected deliveries | 0 |
| accepted sends | 1 |
| persisted sends | 0 |
| mixed HTTP probes | disabled |
| validator result | passed |

## Interpretation

이 결과는 runner와 validator의 end-to-end 계약을 확인하는 작은 local scenario입니다. receiver matrix 계산,
manifest 기록, raw JSONL 재생성 검산, room별 denominator 확인이 연결되는지를 보여주지만, 공개 성능 지표로
사용하지 않습니다.
