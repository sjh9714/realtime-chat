# Receiver Matrix 500-User Local Repeat3 Evidence

이 문서는 2026-05-22에 Docker Compose `app-1` / `app-2` 환경에서 실행한 500-user receiver matrix 3회 반복 결과를 요약합니다.

이 결과는 WebSocket fan-out 경로와 receiver matrix 계산을 더 큰 단일 local room에서 검증한 scenario evidence입니다. 운영 성능 claim, 1,000 session benchmark, mixed traffic p95, room-global ordering claim으로 사용하지 않습니다.

## 실행 조건

| 항목 | 값 |
| --- | --- |
| runtime | Docker Compose app-1/app-2 |
| room users | 500 |
| senders | 5 |
| messages per sender | 20 |
| send interval | 100ms |
| accepted sends per run | 100 |
| expected deliveries per run | 49,900 |
| raw artifact roots | `artifacts/ws/20260522T122502Z-receiver-matrix-500users-run1`, `artifacts/ws/20260522T124030Z-receiver-matrix-500users-run2`, `artifacts/ws/20260522T124126Z-receiver-matrix-500users-run3` |
| sanitized summary | `docs/evidence/receiver-matrix-500users-20260522-summary.json` |

## 결과 요약

| run | accepted sends | expected | unique | missing | duplicate | completeness | p50 | p95 | p99 | max | sender-local order diagnostic |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 100 | 49,900 | 49,900 | 0 | 0 | 100% | 28ms | 47ms | 183ms | 289ms | 311 |
| 2 | 100 | 49,900 | 49,900 | 0 | 0 | 100% | 29ms | 42ms | 233ms | 333ms | 473 |
| 3 | 100 | 49,900 | 49,900 | 0 | 0 | 100% | 28ms | 37ms | 46ms | 87ms | 0 |

## 해석 경계

- 세 번 모두 `accepted sends 100`, `failed sends 0`, `statusless sends 0`입니다.
- 세 번 모두 `expected 49,900`, `unique 49,900`, `missing 0`, `duplicate 0`, `unexpected 0`입니다.
- repeat3 latency snapshot은 p50 28-29ms, p95 37-47ms, p99 46-233ms입니다.
- 이 결과는 local Docker Compose 반복 실행입니다.
- 1,000 session benchmark, room-global ordering, mixed traffic latency, production performance claim은 별도 측정 대상입니다.
- `senderLocalOutOfOrderCount`는 sender-local diagnostic이며 room-global ordering claim으로 사용하지 않습니다.
