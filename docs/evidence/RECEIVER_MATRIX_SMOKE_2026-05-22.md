# Receiver Matrix Smoke Evidence

이 문서는 `scripts/ws-delivery-runner.mjs`와 `scripts/delivery-matrix.mjs`가 실제 app-1/app-2
Docker Compose 환경에서 receiver 기준 delivery matrix를 생성하고 검산할 수 있음을 보여주는 local
smoke evidence입니다.

이 결과는 작은 local low-rate baseline이며, STOMP receipt 기반 subscribe barrier가 아니라 `CONNECTED`
확인 후 settle delay를 사용합니다. 따라서 운영 성능 claim이나 큰 WebSocket benchmark가 아니라, 측정
도구와 fan-out 경로의 검증 근거로만 사용합니다.

`artifacts/ws/**` JSONL 원본 파일은 로컬 실행 산출물이며 git에는 포함하지 않습니다. 이 문서는 당시
명령과 summary snapshot을 보존해 runner 검산 경로를 설명하기 위한 문서입니다.

## Provenance / Caveat

```txt
documented source commit: 4da5ac1ba5c0fcd4f3602b6adcb949d8ed460275
runtime: Docker Compose app-1/app-2
app build/run: Docker Compose 기반 로컬 실행, 정확한 image digest와 build command는 raw artifact에 보존되지 않음
docker daemon at documentation time: CPUs=10, MemTotal=8321712128 bytes
raw JSONL artifacts: local-only unless separately attached
```

Docker resource limit과 image digest가 실행 artifact로 보존되지 않았으므로, 아래 smoke summary는 public
benchmark가 아니라 도구 검증 snapshot으로만 해석합니다.

## Environment

```txt
date: 2026-05-22
runtime: Docker Compose app-1/app-2
app-1: http://localhost:8081 / ws://localhost:8081/ws
app-2: http://localhost:8082 / ws://localhost:8082/ws
```

## Low-rate Smoke

```bash
node scripts/ws-delivery-runner.mjs \
  --base http://localhost:8081 \
  --ws ws://localhost:8081/ws,ws://localhost:8082/ws \
  --users 10 \
  --senders 2 \
  --messages 5 \
  --drain-ms 5000 \
  --out-dir artifacts/ws/20260522-115847-receiver-matrix-smoke-lowrate
```

status denominator 분리 추가 후 같은 low-rate 조건을 다시 실행했습니다.

```bash
node scripts/ws-delivery-runner.mjs \
  --base http://localhost:8081 \
  --ws ws://localhost:8081/ws,ws://localhost:8082/ws \
  --users 10 \
  --senders 2 \
  --messages 5 \
  --drain-ms 5000 \
  --out-dir artifacts/ws/20260522-122919-receiver-matrix-status-lowrate
```

### 도구 검산 snapshot — 공개 지표 금지

아래 내용은 작은 local smoke에서 runner가 expected/actual delivery 분모와 send status 분모를
분리해 계산할 수 있음을 확인한 qualitative summary입니다. 이 구간의 raw percentile과 completeness
숫자는 과거 도구 검증용 smoke였으므로 portfolio metric으로 사용하지 않습니다.

```txt
low-rate smoke summary:
- runner가 expected delivery와 unique delivery를 대조할 수 있음을 확인
- status denominator 추가 후 accepted / persisted / statusless send 분모를 분리할 수 있음을 확인
- public delivery benchmark, send-to-receive latency claim, 운영 성능 수치로 사용하지 않음
```

## Rate-limit-confounded Smoke

아래 조건은 runner가 sender별로 50ms 간격 전송을 수행해 기본 SEND rate limit인 초당 10건을 넘길 수
있습니다. 당시 matrix는 server ACK/NACK/status 로그가 없어 ACK 미수신 또는 rate-limited send를
분모에서 분리하지 못했으므로, `missingDeliveries`는 순수 fan-out 손실로 해석하지 않습니다.

```bash
node scripts/ws-delivery-runner.mjs \
  --base http://localhost:8081 \
  --ws ws://localhost:8081/ws,ws://localhost:8082/ws \
  --users 10 \
  --senders 2 \
  --messages 20 \
  --drain-ms 5000 \
  --out-dir artifacts/ws/20260522-115824-receiver-matrix-smoke
```

```txt
rate-limit-confounded smoke summary:
- runner가 high-rate 조건에서 missing / duplicate / unexpected delivery 진단 값을 만들 수 있음을 확인
- 당시 실행은 ACK 미수신 또는 rate limit에 막힌 send를 expected delivery 분모에서 제외하지 못했음
- public delivery benchmark, send-to-receive latency claim, 전달 성공률 지표로 사용하지 않음
```

## Connected + Settle Rerun

Spring simple broker 환경에서 `SUBSCRIBE` receipt가 돌아오지 않아, runner의 기본 barrier를
`CONNECTED` 확인 + settle delay로 낮춘 뒤 같은 app-1/app-2 환경에서 low-rate receiver matrix를 다시
실행했습니다. 이 실행은 receiver matrix와 status denominator 계산 경로가 동작함을 확인한 local smoke이며,
여전히 공개 benchmark나 운영 성능 claim이 아닙니다.

```bash
node scripts/ws-delivery-runner.mjs \
  --base http://localhost:8081 \
  --ws ws://localhost:8081/ws,ws://localhost:8082/ws \
  --users 10 \
  --senders 2 \
  --messages 5 \
  --send-interval-ms 125 \
  --subscribe-receipt-timeout-ms 5000 \
  --status-subscribe-settle-ms 500 \
  --drain-ms 5000 \
  --out-dir artifacts/ws/20260522T070124Z-receiver-matrix-connected-settle-smoke
```

최신 low-rate baseline은 아래 조건으로 다시 실행했습니다.

```bash
node scripts/ws-delivery-runner.mjs \
  --base http://localhost:8081 \
  --ws ws://localhost:8081/ws,ws://localhost:8082/ws \
  --users 10 \
  --senders 2 \
  --messages 5 \
  --send-interval-ms 125 \
  --subscribe-receipt-timeout-ms 5000 \
  --status-subscribe-settle-ms 500 \
  --drain-ms 5000 \
  --out-dir artifacts/ws/20260522T075845Z-receiver-matrix-lowrate
```

```txt
summary snapshot:
- total sends: 10
- accepted sends: 10
- failed sends: 0
- statusless sends: 0
- expected deliveries: 90
- actual unique deliveries: 90
- missing deliveries: 0
- duplicate deliveries: 0
- unexpected deliveries: 0
- latency sample count: 90
- latency p50: 16ms
- latency p95: 239ms
- latency p99: 240ms
```

이 raw snapshot은 작은 local baseline의 산출물입니다. 따라서 포트폴리오의 큰 시나리오 기준
`메시지 전달 지연 시간`과 `WebSocket 전달 완전성`은 clock/환경 기록, 반복 실행 조건이 갖춰질 때까지
계속 `추가 측정 예정`으로 둡니다.

## Representative 50-user Local Receiver Run

feedback의 `1 room / 50 users / 10 msg/s` 후보에 맞춰, 같은 Docker Compose app-1/app-2 환경에서
단일 방 50명 receiver matrix를 다시 실행했습니다. 이 실행은 raw JSONL을 `artifacts/ws/**`에 남긴
local run이며, 아직 반복 benchmark나 운영 성능 claim은 아닙니다.

```bash
node scripts/ws-delivery-runner.mjs \
  --base http://localhost:8081 \
  --ws ws://localhost:8081/ws,ws://localhost:8082/ws \
  --users 50 \
  --senders 5 \
  --messages 20 \
  --send-interval-ms 100 \
  --subscribe-receipt-timeout-ms 5000 \
  --status-subscribe-settle-ms 1000 \
  --drain-ms 10000 \
  --out-dir artifacts/ws/20260522T081805Z-receiver-matrix-50users-10mps
```

```txt
summary snapshot:
- room users: 50
- total sends: 100
- accepted sends: 100
- failed sends: 0
- statusless sends: 0
- expected deliveries: 4,900
- actual unique deliveries: 4,900
- missing deliveries: 0
- duplicate deliveries: 0
- unexpected deliveries: 0
- latency sample count: 4,900
- latency p50: 18ms
- latency p95: 31ms
- latency p99: 139ms
- sender-local order diagnostic: 3
```

Sanitized summary는 `docs/evidence/receiver-matrix-50users-20260522-summary.json`에 보존했습니다.
`senderLocalOutOfOrderCount`는 sender별 local sequence 진단값이며 room-global ordering claim이 아닙니다.
true room ordering은 persisted message id나 Kafka offset 기반 room-global sequence가 필요합니다.

## Receipt-barrier Diagnostic Attempt

room/status `SUBSCRIBE` receipt를 hard barrier로 강제하면 같은 app-1/app-2 환경에서도 send window 진입 전
timeout이 발생합니다. 이 시도는 delivery completeness나 latency 근거로 사용하지 않습니다.
같은 실패를 재현해야 할 때는 `--require-room-receipts true`와 `--require-status-receipts true`를
사용합니다. runner는 barrier 실패 시에도 partial JSONL과 `failure.json`을 남기므로, 해당 파일은 측정
근거가 아니라 receipt barrier 디버깅 artifact로만 사용합니다.

```bash
node scripts/ws-delivery-runner.mjs \
  --base http://localhost:8081 \
  --ws ws://localhost:8081/ws,ws://localhost:8082/ws \
  --users 6 \
  --senders 2 \
  --messages 10 \
  --send-interval-ms 125 \
  --subscribe-receipt-timeout-ms 15000 \
  --drain-ms 10000
```

```txt
Timed out waiting for required SUBSCRIBE receipts for userId=81
```

## Interpretation

- Low-rate baseline에서는 작은 조건에서 expected/unique delivery 대조와 status denominator 분리가 동작함을
  확인했습니다. raw summary는 단일 local snapshot이며 운영 성능 지표가 아닙니다.
- 50-user local receiver run에서는 단일 방 50명, 100 accepted sends 기준 expected 4,900 / actual unique
  4,900 / missing 0 / duplicate 0을 확인했습니다. 반복 benchmark나 room-global ordering 성능 지표로
  확장하지 않습니다.
- High-rate smoke에서는 ACK 미수신 또는 rate limit에 막힌 send를 expected delivery 분모에서 제외하지
  못했으므로, missing count를 WebSocket fan-out 손실이나 전달 성공률 지표로 해석하지 않습니다.
- `senderLocalOutOfOrderCount`는 sender별 sequence 진단입니다. room 전체 ordering 성능 지표가
  아니며, true room ordering은 persisted message id 또는 Kafka offset 기반 room-global sequence가
  필요합니다.
- 현재 runner는 `status.jsonl`에 ACK/NACK/PERSISTED 상태를 기록하므로, 후속 실행에서는 `accepted` /
  `persisted` / `statusless` / `received` 분모를 분리해 검산해야 합니다. receipt hard barrier 재실행은
  Spring simple broker 환경에서 timeout될 수 있으므로 기본 측정 경로와 분리합니다.
