# Testing Evidence

이 문서는 Realtime Chat의 포트폴리오 claim을 어떤 테스트와 스크립트가 지지하는지 분리합니다. smoke
수치와 공개 benchmark 수치를 구분하고, 운영 성능처럼 보이는 claim은 추가하지 않습니다.

## 검증된 범위

| 범위 | 대표 테스트 / 도구 | 검증하는 주장 |
| --- | --- | --- |
| 채팅방 조회 N+1 제거 | `docs/PERF_RESULT.md`, repository/query 테스트 | 2N+1 쿼리를 1회 쿼리로 줄인 REST 조회 최적화 |
| REST 조회 RPS/p95 개선 | k6 REST 조회 결과 | `GET /api/rooms` 중심 측정 완료 결과 |
| STOMP subscribe authorization | WebSocket integration / interceptor 테스트 | 비멤버 room topic 구독 거부 |
| Kafka ACK/NACK | Kafka publish callback 테스트 | ACCEPTED/FAILED는 Kafka publish 결과임을 분리 |
| DB persisted ACK | persistence consumer / STOMP 테스트 | DB 저장 완료 후 PERSISTED status 전달 |
| Redis Pub/Sub destination routing | `RedisPubSubServiceTest` | pattern channel이 아니라 payload `roomId` 기준 room topic fan-out |
| Redis Pub/Sub server fan-out metric | `RedisPubSubServiceTest` | Redis room channel 수신 후 WebSocket room topic 브로드캐스트 성공 시 `chat.messages.received`, `chat.room.fanout.latency` 기록 |
| receiver matrix runner | `scripts/delivery-matrix-smoke-test.mjs` | member/send/receive/status JSONL 검산 경로와 room별 denominator 분리 |
| delivery evidence validator | `scripts/delivery-evidence-validator-smoke-test.mjs`, `scripts/validate-delivery-evidence.mjs` | manifest, raw JSONL, regenerated summary, byRoom coverage, mixed HTTP failed 0 조건을 대조해 manifest-backed artifact만 승격 |
| delivery evidence validator manifest smoke | `docs/evidence/DELIVERY_EVIDENCE_VALIDATOR_MANIFEST_2026-05-22.md` | 2-user local artifact에서 manifest/raw JSONL/summary/byRoom 검산이 실제로 통과하는지 확인 |
| mixed HTTP probe artifact guard | `scripts/delivery-matrix-smoke-test.mjs` | 선택 실행한 room list / message history / read receipt HTTP probe가 receiver delivery denominator를 바꾸지 않도록 분리 |
| receiver matrix by-room guard | `docs/evidence/DELIVERY_MATRIX_BY_ROOM_GUARD_2026-05-22.md` | cross-room receive가 aggregate에 묻히지 않고 해당 room의 unexpected delivery로 기록됨 |
| receiver matrix 50-user repeat3 | `scripts/ws-delivery-runner.mjs`, `scripts/delivery-matrix.mjs`, `docs/evidence/receiver-matrix-50users-repeat3-20260522-summary.json` | local Docker Compose app-1/app-2에서 3회 모두 expected 4,900 / unique 4,900 / missing 0 / duplicate 0 |
| receiver matrix 500-user repeat3 | `scripts/ws-delivery-runner.mjs`, `scripts/delivery-matrix.mjs`, `docs/evidence/receiver-matrix-500users-20260522-summary.json` | local Docker Compose app-1/app-2에서 3회 모두 expected 49,900 / unique 49,900 / missing 0 / duplicate 0 |
| mixed HTTP probe 10-room/50-user repeat3 | `scripts/ws-delivery-runner.mjs --mixed-http-probes true`, `scripts/validate-delivery-evidence.mjs`, `docs/evidence/mixed-traffic-10rooms-50users-repeat3-20260523-summary.json` | local single app에서 3회 모두 expected 4,900 / unique 4,900 / missing 0 / duplicate 0, mixed HTTP failed 0 |
| DLT replay idempotency | Testcontainers integration | `messageKey` 기준 중복 저장 방지 |
| DLT replay metric | `DltReplayServiceTest` | manual replay 재발행 성공 시 `chat.messages.dlt.replayed` 기록 |
| rooms cache eviction metric | `MessagePersistenceConsumerCacheTest` | 메시지 저장 후 room member cache eviction counter 기록 |
| read receipt 정합성 | service / integration 테스트 | sender 제외, joinedAt 이전 메시지 제외 |

## 아직 검증하지 않는 범위

| 범위 | 현재 상태 |
| --- | --- |
| production 1,000 session send-to-receive p50/p95/p99 | 1,000-user local repeat3는 시나리오 검증, production benchmark는 추가 측정 예정 |
| production 1,000 session receiver delivery completeness | 1,000-user local repeat3는 시나리오 검증이며 public benchmark가 아님 |
| room-global ordering benchmark | persisted message id 기준 local diagnostic은 있으나 production benchmark는 아직 없음 |
| production mixed traffic p95 | 10-room/50-user local mixed HTTP probe repeat3는 시나리오 검증, production/cache hit benchmark는 추가 측정 예정 |
| Redis rate-limit 알고리즘 비교 | fixed-window 한계를 문서화했고 sliding window/token bucket 비교는 아직 없음 |

## 실행 명령

```bash
node --check scripts/ws-delivery-runner.mjs
node --check scripts/delivery-matrix.mjs
node --check scripts/delivery-matrix-smoke-test.mjs
node scripts/delivery-matrix-smoke-test.mjs
./gradlew test --no-daemon
./gradlew build --no-daemon
```

## 해석 원칙

- ACK/PERSISTED는 상대 클라이언트 수신 완료가 아닙니다.
- receiver matrix smoke의 raw JSON은 도구 검증 snapshot이며 공개 성능 수치로 사용하지 않습니다.
- 2026-05-22 receiver matrix 요약은 historical local scenario evidence입니다. 현재 validator certification은
  `manifest.json`이 포함된 future artifact에 적용합니다.
- mixed HTTP probe는 REST 조회/읽음 처리 경로가 같은 artifact에 남는지 확인하는 보조 로그이며, WebSocket
  delivery completeness 분모에 섞지 않습니다.
- mixed chat smoke는 REST 조회, WebSocket 연결, ACK, 발신자 self echo, read receipt 경로 실행 확인이며
  공개 mixed traffic benchmark가 아닙니다.
- 10-room/50-user mixed HTTP probe repeat3는 local 단일 앱 시나리오 검증이며 production benchmark가 아닙니다.
- 실시간 메시징 품질 claim은 latency, completeness, duplicate, ordering을 함께 기록한 뒤 올립니다.
