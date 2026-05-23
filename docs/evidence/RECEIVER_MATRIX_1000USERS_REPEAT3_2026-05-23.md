# Receiver Matrix 1,000-user Repeat3 Evidence

이 문서는 2026-05-23에 Docker Compose `app-1` / `app-2` 환경에서 실행한 1,000-user receiver matrix 3회 반복 결과를 요약합니다.

이 결과는 local scenario evidence입니다. 운영 성능 claim, mixed traffic benchmark, production p95 claim으로 사용하지 않습니다. `ACCEPTED` / `PERSISTED` ACK는 전송 상태 진단이며, recipient delivery는 receiver JSONL 기준으로 별도 계산했습니다.

## 실행 조건

| 항목 | 값 |
| --- | --- |
| runtime | Docker Compose app-1/app-2 |
| room users | 1,000 |
| senders | 5 |
| messages per sender | 20 |
| send interval | 100ms |
| messages per run | 100 |
| expected deliveries per run | 99,900 |
| raw artifact root | `artifacts/ws/20260523T005158Z-receiver-matrix-1000users-repeat3` |
| sanitized summary | `docs/evidence/receiver-matrix-1000users-repeat3-20260523-summary.json` |

## 결과 요약

| run | accepted sends | persisted sends | expected | unique | missing | duplicate | completeness | p50 | p95 | p99 | max | room-global order source | out-of-order |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- | ---: |
| 1 | 100 | 100 | 99,900 | 99,900 | 0 | 0 | 100% | 30ms | 45ms | 75ms | 98ms | persistedMessageId | 0 |
| 2 | 100 | 100 | 99,900 | 99,900 | 0 | 0 | 100% | 31ms | 50ms | 75ms | 90ms | persistedMessageId | 0 |
| 3 | 100 | 100 | 99,900 | 99,900 | 0 | 0 | 100% | 30ms | 45ms | 58ms | 70ms | persistedMessageId | 0 |

## 해석 경계

- 세 번 모두 `accepted sends 100`, `persisted sends 100`, `statusless sends 0`, `failed sends 0`입니다.
- 세 번 모두 `expected 99,900`, `unique 99,900`, `missing 0`, `duplicate 0`, `unexpected 0`입니다.
- p50 범위는 30-31ms, p95 범위는 45-50ms, p99 범위는 58-75ms입니다.
- room-global ordering 검산은 raw receive rows에 노출된 persisted DB `messageId`를 기준으로 계산했고, 세 번 모두 `outOfOrderCount 0`입니다.
- 이 결과는 같은 local Docker Compose 환경의 반복 실행입니다. 운영 환경 성능, mixed traffic latency, 장시간 soak, 다중 room 분산 benchmark로 확장하지 않습니다.
