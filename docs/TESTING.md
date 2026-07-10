# Testing Evidence

이 문서는 현재 Realtime Chat 코드의 정합성 주장을 어떤 테스트가 지지하는지 정리합니다.
`PERF_RESULT.md`, `WEBSOCKET_MEASUREMENT.md`, `docs/evidence`의 과거 측정값은 commit pin이 없는
historical archive입니다. 현재 room-list와 persistence pipeline의 성능 evidence로 사용하지 않으며,
처리량과 지연 시간 주장은 현재 commit 재측정 뒤로 미룹니다.

## 검증된 범위

| 범위 | 대표 테스트 / 도구 | 검증하는 주장 |
| --- | --- | --- |
| 채팅방 목록 query shape | repository/query 테스트 | room 목록을 projection query로 조회하는 현재 구조 |
| STOMP inbound authorization | `WebSocketSubscribeAuthorizationIntegrationTest`, interceptor 테스트 | 비멤버 구독과 outsider의 `/topic` 직접 SEND exploit 거부 |
| public demo boundary | `PublicDemoProfileBoundaryIntegrationTest` | `docker-compose.demo.yml` 공개 데모에서는 `/api/demo/**` diagnostics가 `404` |
| production profile boundary | `ProdDemoProfileBoundaryIntegrationTest`, `ProdE2eProfileBoundaryIntegrationTest` | `prod+demo`(`prod,demo`)와 `prod+e2e`(`prod,e2e`) 모두 seed/controller/failure probe 미등록, `/api/demo/**` `404` |
| Kafka ACK/NACK | Kafka publish callback 테스트 | ACCEPTED/FAILED는 Kafka publish 결과임을 분리 |
| commit-before-broadcast | `MessagePersistenceConsumerPublishTest`, STOMP 테스트 | PostgreSQL commit 뒤에만 room payload와 PERSISTED status 전달 |
| client retry idempotency | `MessagePersistenceServiceTest`, browser E2E | 같은 sender/clientMessageId는 DB 1건, receiver delivery 1회 |
| cache failure isolation | `MessagePersistenceCacheFailureIntegrationTest` | afterCommit Redis cache eviction 실패에도 message와 unread commit 유지 |
| Redis Pub/Sub destination routing | `RedisPubSubServiceTest` | pattern channel이 아니라 payload `roomId` 기준 room topic fan-out |
| Redis Pub/Sub server fan-out metric | `RedisPubSubServiceTest` | Redis room channel 수신 후 WebSocket room topic 브로드캐스트 성공 시 `chat.messages.received`, `chat.room.fanout.latency` 기록 |
| receiver matrix runner | `scripts/delivery-matrix-smoke-test.mjs` | member/send/receive/status JSONL 검산 경로와 room별 denominator 분리 |
| delivery evidence validator | `scripts/delivery-evidence-validator-smoke-test.mjs`, `scripts/validate-delivery-evidence.mjs` | manifest, raw JSONL, regenerated summary와 byRoom coverage의 일치 여부 검산 |
| archived validator fixture | `docs/evidence` | validator 회귀용 historical fixture. 현재 코드 성능 evidence는 아님 |
| mixed HTTP probe artifact guard | `scripts/delivery-matrix-smoke-test.mjs` | 선택 실행한 room list / message history / read receipt HTTP probe가 receiver delivery denominator를 바꾸지 않도록 분리 |
| receiver matrix by-room guard | deterministic validator fixture | cross-room receive가 aggregate에 묻히지 않고 해당 room의 unexpected delivery로 기록됨 |
| DLT replay idempotency | Testcontainers integration | `messageKey` 기준 중복 저장 방지 |
| DLT replay metric | `DltReplayServiceTest` | manual replay 재발행 성공 시 `chat.messages.dlt.replayed` 기록 |
| rooms cache eviction metric | `MessagePersistenceServiceTest` | commit 이후 room member cache eviction counter 기록 |
| two-node recovery E2E | Playwright + demo/e2e Compose overlay | raw Alice와 Bob의 실제 WebSocket upgrade 응답에서 app이 넣은 `x-app-instance`를 확인해 서로 다른 노드임을 증명한 뒤 cross-node delivery/recovery 검증 |
| client delivery state | `chat store delivery reconciliation` Vitest | 동일 optimistic 메시지의 `SENDING → ACCEPTED → PERSISTED` 전이를 각 중간 상태에서 검증 |
| client runtime contract | Vitest + Zod | REST/STOMP payload 형식 위반 거부와 401 session clear |
| read receipt 정합성 | service / integration 테스트 | sender 제외, joinedAt 이전 메시지 제외 |

## 아직 검증하지 않는 범위

| 범위 | 현재 상태 |
| --- | --- |
| current-code performance benchmark | 현재 commit·환경·명령·artifact를 함께 고정한 재측정 전까지 공개하지 않음 |
| production send-to-receive latency와 delivery completeness | local correctness E2E를 production 성능 보장으로 확장하지 않음 |
| room-global ordering benchmark | 구현 계약은 room partition 범위이며 전역 ordering 성능을 주장하지 않음 |
| production mixed traffic와 cache hit rate | 현재 pipeline 기준 재측정하지 않음 |
| Redis rate-limit 알고리즘 비교 | fixed-window 한계를 문서화했고 sliding window/token bucket 비교는 아직 없음 |

## 실행 명령

```bash
node --check scripts/ws-delivery-runner.mjs
node --check scripts/delivery-matrix.mjs
node --check scripts/delivery-matrix-smoke-test.mjs
node scripts/delivery-matrix-smoke-test.mjs
./gradlew test --no-daemon
./gradlew build --no-daemon
cd web
npm test
npm run build
```

공개 one-click demo는 `docker-compose.demo.yml`만 사용합니다. fault diagnostics와 두 app instance를
확인하는 Playwright는 테스트 전용 overlay를 함께 올립니다.

```bash
docker compose -f docker-compose.demo.yml -f docker-compose.e2e.yml up --build --wait
(cd web && npm run e2e)
docker compose -f docker-compose.demo.yml -f docker-compose.e2e.yml down --volumes
```

## 해석 원칙

- ACK/PERSISTED는 상대 클라이언트 수신 완료가 아닙니다.
- receiver matrix raw JSON과 기존 요약은 validator 회귀용 historical fixture이며 현재 코드의 공개 성능
  수치로 사용하지 않습니다.
- mixed HTTP probe는 REST 조회/읽음 처리 경로가 같은 artifact에 남는지 확인하는 보조 로그이며, WebSocket
  delivery completeness 분모에 섞지 않습니다.
- mixed chat smoke는 REST 조회, WebSocket 연결, ACK, 발신자 self echo, read receipt 경로 실행 확인이며
  공개 mixed traffic benchmark가 아닙니다.
- 성능 수치는 현재 commit과 raw artifact를 함께 고정해 다시 측정한 뒤에만 공개합니다.
