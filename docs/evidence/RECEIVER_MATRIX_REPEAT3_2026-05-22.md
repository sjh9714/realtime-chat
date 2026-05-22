# Receiver Matrix Repeat3 Evidence

이 문서는 2026-05-22에 Docker Compose `app-1` / `app-2` 환경에서 실행한 50-user receiver matrix 3회 반복 결과를 요약합니다.

이 결과는 WebSocket fan-out 경로와 receiver matrix 계산을 검증하는 local scenario evidence입니다. 운영 성능 claim, 500/1,000 session benchmark, mixed traffic p95, room-global ordering claim으로 사용하지 않습니다.

## 실행 조건

| 항목 | 값 |
| --- | --- |
| runtime | Docker Compose app-1/app-2 |
| room users | 50 |
| senders | 5 |
| messages per sender | 20 |
| send interval | 100ms |
| messages per run | 100 |
| expected deliveries per run | 4,900 |
| raw artifact root | `artifacts/ws/20260522T114435Z-receiver-matrix-50users-repeat3` |
| sanitized summary | `docs/evidence/receiver-matrix-50users-repeat3-20260522-summary.json` |

## 결과 요약

| run | accepted sends | expected | unique | missing | duplicate | completeness | p50 | p95 | p99 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 100 | 4,900 | 4,900 | 0 | 0 | 100% | 18ms | 38ms | 127ms | 229ms |
| 2 | 100 | 4,900 | 4,900 | 0 | 0 | 100% | 16ms | 23ms | 36ms | 47ms |
| 3 | 100 | 4,900 | 4,900 | 0 | 0 | 100% | 16ms | 24ms | 27ms | 32ms |

## 해석 경계

- 세 번 모두 `accepted sends 100`, `statusless sends 0`, `failed sends 0`입니다.
- 세 번 모두 `expected 4,900`, `unique 4,900`, `missing 0`, `duplicate 0`입니다.
- p50 범위는 16-18ms, p95 범위는 23-38ms, p99 범위는 27-127ms입니다.
- 이 결과는 같은 local Docker Compose 환경의 반복 실행입니다.
- 500/1,000 session benchmark, room-global ordering, mixed traffic latency, production performance claim은 별도 측정 대상입니다.
