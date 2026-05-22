# Mixed Chat Smoke Evidence

이 문서는 `k6/mixed-chat-test.js`가 local 단일 인스턴스 환경에서 REST 조회, WebSocket 연결,
메시지 전송, ACK 수신, 발신자 self echo 관측, 읽음 처리 API 경로를 함께 실행할 수 있음을 보여주는
smoke evidence입니다.

이 결과는 `SMOKE=1`, 1 VU, 짧은 local 실행입니다. 따라서 mixed traffic RPS/p95 benchmark,
실제 수신자 기준 send-to-receive latency, 전체 delivery completeness, 운영 성능 claim으로 사용하지
않습니다.

## Environment

```txt
date: 2026-05-22
app: http://localhost:8081
websocket: ws://localhost:8081/ws
script: k6/mixed-chat-test.js
mode: SMOKE=1
users created by setup: 3
max VUs: 1
```

## Command

```bash
k6 run \
  --env BASE_URL=http://localhost:8081 \
  --env WS_URL=ws://localhost:8081/ws \
  --env SMOKE=1 \
  --env NUM_USERS=3 \
  --summary-export=docs/evidence/k6/mixed-chat-smoke-20260522T092738Z-summary.json \
  k6/mixed-chat-test.js
```

## Sanitized Artifacts

```txt
console: docs/evidence/k6/mixed-chat-smoke-20260522T092738Z-console.txt
summary: docs/evidence/k6/mixed-chat-smoke-20260522T092738Z-summary.json
```

`summary.json`의 setup token은 `<redacted>`로 치환했습니다.

## Smoke Summary

```txt
checks: 37 succeeded / 0 failed
iterations: 9
http_req_failed: 0%
mixed_error_rate: 0%
ack_success_rate: 100%
nack_rate: 0%
ws_connection_success_rate: 100%
delivery_success_rate: 100% (발신자 self echo 관측 기준)
message_send_ack_latency p95: 8ms
send_to_receive_latency p95: 8ms (발신자 self echo 관측 기준)
http_req_duration p95: 76.0175ms
```

## Interpretation

- 이 smoke는 mixed scenario가 local 단일 인스턴스에서 깨지지 않고 실행됨을 확인합니다.
- `delivery_success_rate`와 `send_to_receive_latency`는 발신자가 자기 메시지를 room topic에서 다시
  관측한 경우만 의미합니다.
- 수신자 전체 fan-out completeness, receiver 기준 latency, room-global ordering은 이 결과로 주장하지
  않습니다.
- public portfolio claim은 기존 REST 조회 RPS/p95, receiver matrix local snapshot, 그리고 별도
  문서화된 pending 항목을 기준으로 유지합니다.
