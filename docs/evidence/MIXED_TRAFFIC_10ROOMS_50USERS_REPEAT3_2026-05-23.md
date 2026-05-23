# Mixed Traffic 10-Room 50-User Repeat3 Evidence

이 문서는 2026-05-23에 local JVM 단일 앱과 격리된 Docker Postgres/Redis/Kafka 환경에서 실행한 mixed HTTP probe 포함 receiver matrix 3회 반복 결과를 요약합니다.

이 결과는 local scenario evidence입니다. 운영 성능 claim, production p95 claim, 장시간 soak, cache hit ratio claim으로 사용하지 않습니다. HTTP probe는 WebSocket drain 이후 같은 artifact에 남긴 보조 로그이며, receiver delivery denominator에는 포함하지 않습니다.

## 실행 조건

| 항목 | 값 |
| --- | --- |
| runtime | local JVM single app on `localhost:8081` |
| infra | isolated Docker Postgres `55432`, Redis `56379`, Kafka `39092` |
| rooms | 10 |
| users per room | 50 |
| total WebSocket sessions | 500 |
| senders per room | 1 |
| messages per sender | 10 |
| send interval | 100ms |
| messages per run | 100 |
| expected deliveries per run | 4,900 |
| mixed HTTP probes | room list 10, message history 10, read receipt 10 per run |
| raw artifact root | `artifacts/ws/20260523T014727Z-mixed-traffic-10rooms-50users-repeat3` |
| sanitized summary | `docs/evidence/mixed-traffic-10rooms-50users-repeat3-20260523-summary.json` |
| validator | `node scripts/validate-delivery-evidence.mjs --artifact-dir <run-dir>` passed for all 3 runs |

## 결과 요약

| run | accepted sends | persisted sends | expected | unique | missing | duplicate | completeness | p50 | p95 | p99 | max | HTTP failed | room-global out-of-order |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 100 | 100 | 4,900 | 4,900 | 0 | 0 | 100% | 13ms | 20ms | 39ms | 81ms | 0 / 30 | 0 |
| 2 | 100 | 100 | 4,900 | 4,900 | 0 | 0 | 100% | 13ms | 18ms | 20ms | 23ms | 0 / 30 | 0 |
| 3 | 100 | 100 | 4,900 | 4,900 | 0 | 0 | 100% | 13ms | 20ms | 26ms | 27ms | 0 / 30 | 0 |

## Mixed HTTP probe p95

| run | rooms list p95 | message history p95 | read receipt p95 |
| --- | ---: | ---: | ---: |
| 1 | 21ms | 13ms | 19ms |
| 2 | 16ms | 10ms | 9ms |
| 3 | 25ms | 14ms | 14ms |

## 해석 경계

- 세 번 모두 `accepted sends 100`, `persisted sends 100`, `failed sends 0`, `statusless sends 0`입니다.
- 세 번 모두 `expected 4,900`, `unique 4,900`, `missing 0`, `duplicate 0`, `unexpected 0`입니다.
- receiver 기준 send-to-receive p95 범위는 18-20ms입니다.
- mixed HTTP probe는 세 번 모두 `failedRequests 0`입니다.
- room-global ordering 검산은 raw receive rows에 노출된 persisted DB `messageId`를 기준으로 계산했고, 세 번 모두 `outOfOrderCount 0`입니다.
- 이 결과는 local 단일 앱 반복 실행입니다. multi-instance scale-out, 1,000-session mixed benchmark, production benchmark, cache hit ratio로 확장하지 않습니다.
