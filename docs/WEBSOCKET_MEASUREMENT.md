# WebSocket latency / delivery measurement plan

이 문서는 WebSocket 메시지의 send-to-receive latency와 delivery completeness를 측정하기 위한 계획이다.

receiver matrix low-rate baseline, 50-user local receiver repeat3, 500-user local receiver repeat3,
1,000-user local receiver repeat3, 10-room/50-user mixed HTTP probe repeat3는 측정 도구와 fan-out
경로 검증 근거로 보존한다. 다만 production latency, 장시간 soak, cache hit ratio, 1,000-session mixed
benchmark는 아직 없으므로 production/mixed benchmark는 `추가 측정 예정`으로 유지한다.

## 1. 측정하지 않는 것

아래 이벤트는 중요하지만 recipient delivery 결과가 아니다.

| 이벤트 | 의미 | recipient 수신 완료 여부 |
| --- | --- | --- |
| `ACCEPTED` ACK | Kafka broker가 publish 요청을 accepted | 아님 |
| `PERSISTED` ACK | DB 저장 완료 또는 기존 idempotent row 확인 | 아님 |
| sender self echo | 발신자가 구독 중인 room topic에서 자기 메시지를 관측 | 전체 recipient delivery가 아님 |
| WebSocket connection smoke | 연결, 구독, 제한된 송수신 흐름 확인 | 모든 메시지 수신 완료가 아님 |

따라서 `k6/mixed-chat-test.js`의 현재 `send_to_receive_latency`와 `delivery_success_rate`는 "발신자가 자기 메시지를 room topic에서 다시 관측한 경우"만 기록한다. 이 값은 전체 수신자 기준 delivery completeness로 발표하지 않는다.

## 2. send-to-receive latency 정의

```text
send-to-receive latency =
sender client가 STOMP SEND frame을 socket에 기록하기 직전 시각
-> receiver client가 /topic/room.{roomId} MESSAGE frame을 파싱한 시각
```

측정 원칙:

- sender와 receiver가 같은 load generator clock을 사용하도록 우선 구성한다.
- 여러 load generator를 쓰는 경우 clock skew를 별도 기록하고, 절대 latency 수치 해석에 반영한다.
- 발신자 self echo latency와 recipient latency를 분리한다.
- ACK latency, persisted latency, recipient receive latency를 같은 차트에 섞지 않는다.

## 3. delivery completeness 정의

primary metric은 sender를 제외한 room member recipient 기준으로 계산한다.

```text
expected deliveries =
테스트 시작 barrier 이후 구독 완료된 room member 수 - sender 1명
위 값을 각 전송 메시지마다 합산

actual deliveries =
각 receiver가 messageKey 또는 clientMessageId 기준으로 실제 수신한 unique delivery 수

delivery completeness % =
actual unique deliveries / expected deliveries * 100
```

예시 계산 방식:

| 항목 | 값 |
| --- | --- |
| room users | 50 |
| messages sent | 1,000 |
| expected deliveries | 49,000 |
| actual deliveries | 실행 후 기록 |
| missing deliveries | 실행 후 기록 |
| duplicate deliveries | 실행 후 기록 |
| completeness | 실행 후 기록 |

위 예시 조건의 `actual`, `missing`, `duplicate`, `completeness`는 아직 반복 benchmark로 측정하지 않았다.

## 4. 로그 스키마

측정 스크립트 또는 helper는 최소한 아래 로그를 남겨야 한다.

### Send log

| 필드 | 설명 |
| --- | --- |
| `runId` | 실행 식별자 |
| `roomId` | 채팅방 id |
| `senderUserId` | 발신자 id |
| `clientMessageId` | client retry / correlation id |
| `messageKey` | 수신 frame에서 확인된 event identity, 송신 시점에는 비어 있을 수 있음 |
| `roomSequence` | 테스트 클라이언트가 sender별로 부여한 sender-local sequence |
| `sendStartedAtMs` | SEND frame 기록 직전 client timestamp |
| `payloadBytes` | 메시지 payload 크기 |

### Receive log

| 필드 | 설명 |
| --- | --- |
| `runId` | 실행 식별자 |
| `roomId` | 채팅방 id |
| `receiverUserId` | 수신자 id |
| `senderUserId` | frame body의 sender id |
| `clientMessageId` | frame body의 clientMessageId |
| `messageKey` | frame body의 messageKey |
| `roomSequence` | content marker 또는 별도 field로 복원한 sender-local sequence |
| `receivedAtMs` | MESSAGE frame 파싱 시각 |

### Status log

| 필드 | 설명 |
| --- | --- |
| `runId` | 실행 식별자 |
| `roomId` | 채팅방 id |
| `userId` | ACK/NACK/PERSISTED 또는 STOMP ERROR를 관측한 사용자 id |
| `clientMessageId` | ACK/NACK/PERSISTED correlation id. STOMP ERROR는 없을 수 있음 |
| `status` | `accepted`, `failed`, `persisted`, `stomp_error` |
| `serverStatus` | 서버 payload의 원본 status 값 |
| `reason` | 실패 또는 STOMP ERROR 사유 |
| `observedAtMs` | 상태 frame 파싱 시각 |

## 5. 산출 지표

| 지표 | 상태 | 산출 방식 |
| --- | --- | --- |
| send-to-receive p50 / p90 / p95 / p99 / max | 50/500/1,000-user local repeat3 기록, public benchmark 아님 | receive log와 send log를 `clientMessageId`로 join |
| expected deliveries | 50/500/1,000-user local repeat3 기록, public benchmark 아님 | accepted sends 기준 구독 member matrix에서 산출 |
| accepted-send expected deliveries | 50/500/1,000-user local repeat3 기록, public benchmark 아님 | `ACCEPTED`가 확인된 SEND만 분모로 산출 |
| persisted-send expected deliveries | 1,000-user local repeat3 기록, public benchmark 아님 | `PERSISTED`가 확인된 SEND만 분모로 산출 |
| statusless sends | 50/500/1,000-user local repeat3 기록, public benchmark 아님 | drain 종료까지 ACK/NACK/PERSISTED가 없는 SEND 시도 |
| actual unique deliveries | 50/500/1,000-user local repeat3 기록, public benchmark 아님 | `(receiverUserId, clientMessageId)` unique count |
| missing deliveries | 50/500/1,000-user local repeat3 기록, public benchmark 아님 | expected matrix - actual unique deliveries |
| duplicate deliveries | 50/500/1,000-user local repeat3 기록, public benchmark 아님 | 동일 receiver에게 같은 메시지가 2회 이상 도착한 건수 |
| sender-local order diagnostic | local snapshot 기록 | receiver + room + sender별 sender-local sequence 단조 증가 위반 |
| room-global ordering diagnostic | 1,000-user local repeat3 기록, public benchmark 아님 | persisted DB `messageId`가 raw artifact에 있으면 receiver + room별 단조 증가 위반 |

`scripts/ws-delivery-runner.mjs`는 실제 STOMP client를 여러 개 띄워 member / send /
receive / status JSONL을 생성하는 local smoke runner입니다. `scripts/delivery-matrix.mjs`는
해당 로그를 로컬에서 검산해 delivery completeness와 latency percentile 후보를 계산합니다.
필요하면 `--mixed-http-probes true`를 켜서 같은 run artifact 안에 room list, message history,
read receipt HTTP probe를 `http.jsonl`로 남길 수 있습니다. 이 보조 로그는 REST 경로 관측용이며
receiver delivery denominator에는 포함하지 않습니다.

Runner는 `CONNECTED` frame을 받은 뒤 room topic, ACK, ERROR, PERSISTED queue에 `SUBSCRIBE`
frame을 보냅니다. 기본 send window barrier는 `CONNECTED` 확인과 250ms settle delay입니다.
Spring simple broker/user-destination 경로에서 `SUBSCRIBE` receipt가 항상 돌아오지 않을 수 있으므로,
receipt는 기본적으로 진단값으로만 기록합니다.

room topic receipt까지 강제로 요구해 broker 동작을 디버깅하려면 `--require-room-receipts true`를
사용합니다. status queue receipt까지 함께 확인하려면 `--require-status-receipts true`를 추가합니다.
receipt timeout은 기본 5초이며, 필요하면 `--subscribe-receipt-timeout-ms`로 조정합니다. 이 hard
barrier는 receiver matrix 준비 상태를 더 엄격하게 만들지만, 공개 성능 수치로 올리려면 여전히 실행 로그와
조건을 함께 검토해야 합니다.

```bash
node scripts/ws-delivery-runner.mjs \
  --base http://localhost:8081 \
  --ws ws://localhost:8081/ws,ws://localhost:8082/ws \
  --users 10 \
  --senders 2 \
  --messages 5 \
  --send-interval-ms 125 \
  --subscribe-receipt-timeout-ms 5000 \
  --status-subscribe-settle-ms 250 \
  --drain-ms 5000 \
  --out-dir artifacts/ws/smoke
```

```bash
node scripts/delivery-matrix.mjs \
  --members artifacts/ws/smoke/members.jsonl \
  --send artifacts/ws/smoke/send.jsonl \
  --receive artifacts/ws/smoke/receive.jsonl \
  --status artifacts/ws/smoke/status.jsonl \
  --http artifacts/ws/smoke/http.jsonl \
  --json-out artifacts/ws/smoke/summary.json
```

선택적 mixed HTTP probe를 함께 남길 때는 아래 옵션을 추가합니다. 이 실행도 artifact 검토 전에는 공개
benchmark로 사용하지 않습니다.

```bash
node scripts/ws-delivery-runner.mjs \
  --base http://localhost:8081 \
  --ws ws://localhost:8081/ws,ws://localhost:8082/ws \
  --users 10 \
  --senders 2 \
  --messages 5 \
  --mixed-http-probes true \
  --http-probe-users-per-room 1 \
  --drain-ms 5000 \
  --out-dir artifacts/ws/smoke-with-http
```

```bash
node scripts/delivery-matrix-smoke-test.mjs
```

Multi-room receiver matrix 후보를 만들 때는 같은 runner에 room partition 옵션을 붙입니다. 아래 명령은
도구가 2개 room의 member/send/receive matrix를 만들 수 있는지 확인하기 위한 형태이며, 실행 artifact를
검토하기 전까지 공개 성능 수치로 사용하지 않습니다.

```bash
node scripts/ws-delivery-runner.mjs \
  --base http://localhost:8081 \
  --ws ws://localhost:8081/ws,ws://localhost:8082/ws \
  --rooms 2 \
  --users-per-room 3 \
  --senders-per-room 1 \
  --messages 2 \
  --send-interval-ms 100 \
  --drain-ms 5000 \
  --out-dir artifacts/ws/multi-room-smoke
```

`status.jsonl`에는 `/user/queue/messages/ack`, `/user/queue/messages/error`,
`/user/queue/messages/persisted`에서 관측한 `ACCEPTED`, `FAILED`, `PERSISTED` 상태를 기록합니다.
`delivery-matrix.mjs`는 전체 SEND 시도 수와 별도로 `acceptedDelivery`, `persistedDelivery`,
`statuslessSends`를 계산해 rate limit 또는 ACK 미수신 시도를 recipient delivery 분모와 분리합니다.
또한 `byRoom` summary를 함께 만들어 room별 expected/actual/missing/unexpected denominator를 분리합니다.
이는 multi-room runner 결과를 검토할 때 특정 room의 누락이나 cross-room receive가 aggregate에 묻히지
않게 하려는 guard입니다.
`http.jsonl`을 전달하면 `mixedHttp` summary가 추가되지만, receiver matrix의 expected/actual/missing
값은 바뀌지 않습니다.
Rate limit은 inbound interceptor에서 STOMP `ERROR`로 발생하며 `clientMessageId`가 없을 수 있으므로,
clientMessageId로 상관관계가 확인되지 않은 오류는 별도 진단값으로만 남깁니다.

성공한 runner 실행은 `manifest.json`도 함께 남깁니다. manifest는 실행 옵션, Node/플랫폼 환경, expected
sessions/rooms/messages, room id, mixed HTTP probe 포함 여부, 그리고 claim boundary를 기록합니다. 이 값은
새 지표가 아니라 artifact가 어떤 시나리오를 주장할 수 있는지 제한하기 위한 검산 입력입니다.

artifact를 문서 근거로 승격하기 전에는 validator를 먼저 실행합니다.

```bash
node scripts/validate-delivery-evidence.mjs \
  --artifact-dir artifacts/ws/smoke-with-http
```

validator는 `summary.json`, raw JSONL, `manifest.json`이 서로 맞는지 확인하고, 같은 raw 로그로
`delivery-matrix.mjs` summary를 다시 생성해 주요 필드를 비교합니다. `statusless`, `failed`, `missing`,
`duplicate`, `unexpected` 값이 0이 아니어도 scenario-validation artifact로는 허용합니다. 단, manifest가
mixed HTTP probe 포함을 기록한 경우 `mixedHttp.failedRequests`는 0이어야 합니다.

`delivery-matrix-smoke-test.mjs`는 deterministic fixture로 `byRoom` summary와 cross-room unexpected
delivery counting을 검증합니다. 실행 기록은
[`docs/evidence/DELIVERY_MATRIX_BY_ROOM_GUARD_2026-05-22.md`](evidence/DELIVERY_MATRIX_BY_ROOM_GUARD_2026-05-22.md)에
분리했습니다.

`delivery-evidence-validator-smoke-test.mjs`는 validator가 정상 artifact를 통과시키고, manifest/summary가
부족한 artifact를 거부하는지 확인합니다.

```bash
node scripts/delivery-evidence-validator-smoke-test.mjs
```

2026-05-22에는 실제 2-user local receiver matrix artifact에 대해 `manifest.json`, raw JSONL,
`summary.json`, `byRoom` coverage를 validator로 대조했습니다. 자세한 내용은
[`docs/evidence/DELIVERY_EVIDENCE_VALIDATOR_MANIFEST_2026-05-22.md`](evidence/DELIVERY_EVIDENCE_VALIDATOR_MANIFEST_2026-05-22.md)를
참고합니다. 이 결과는 validator 계약 확인용 시나리오 검증이며, 500/1,000 session benchmark나 운영 성능
claim으로 사용하지 않습니다.

도구 입력은 실제 측정 로그여야 합니다. 샘플이나 추정값을 `docs/PERF_RESULT.md`에 측정 완료로
옮기지 않습니다.

### 5-1. Receiver matrix low-rate baseline

2026-05-22에 Docker Compose app-1/app-2를 대상으로 receiver matrix low-rate baseline을 실행했습니다.
이 실행은 `status.jsonl` 생성, accepted/persisted/statusless 분모 분리, 누락/중복 검산 경로를 확인하기
위한 작은 local baseline입니다. 운영 성능 claim이나 큰 WebSocket delivery benchmark로 사용하지 않습니다.
원본 요약은
[`docs/evidence/RECEIVER_MATRIX_SMOKE_2026-05-22.md`](evidence/RECEIVER_MATRIX_SMOKE_2026-05-22.md)에
분리했습니다.

| 확인 항목 | 결과 |
| --- | --- |
| runner 로그 생성 | member / send / receive / status JSONL 생성 확인 |
| status denominator | accepted 10, failed 0, statusless 0 |
| matrix 검산 | expected 90, unique delivery 90, missing 0, duplicate 0, unexpected 0 |
| latency snapshot | p50 16ms, p95 239ms, p99 240ms |

이 baseline은 sample size가 작고 단일 local run이므로, `send_to_receive_latency`나 delivery success rate를
운영 성능 또는 큰 부하 측정 완료 지표로 올리지는 않습니다. 현재 `roomSequence`는 sender별 sequence이므로
`sender-local order diagnostic`은 smoke 진단용이며 room 전체 ordering 성능 지표가 아닙니다. true room
ordering은 persisted message id나 Kafka offset 같은 room-global sequence를 기록한 뒤 별도 시나리오에서
검증해야 합니다.

### 5-2. 50-user local receiver run

2026-05-22에 같은 Docker Compose app-1/app-2 환경에서 단일 방 50명 receiver matrix를 실행했습니다.
이 실행은 feedback의 `1 room / 50 users / 10 msg/s` 후보에 가까운 local representative run이며,
반복 benchmark나 운영 성능 claim으로 사용하지 않습니다.

| 항목 | 값 |
| --- | --- |
| 조건 | room users 50, senders 5, sender당 20 messages, send interval 100ms |
| status denominator | accepted 100, failed 0, statusless 0 |
| matrix 검산 | expected 4,900, unique delivery 4,900, missing 0, duplicate 0, unexpected 0 |
| latency snapshot | p50 18ms, p95 31ms, p99 139ms |
| sender-local order diagnostic | 3 |
| sanitized summary | `docs/evidence/receiver-matrix-50users-20260522-summary.json` |

이 결과는 fan-out 경로와 receiver matrix 계산이 더 큰 단일 방에서도 동작함을 보여주는 local snapshot입니다.
`sender-local order diagnostic`은 room-global ordering 성능 지표가 아니므로, room ordering claim은 여전히
persisted message id 또는 Kafka offset 기반 검증이 필요합니다.

### 5-2-1. 50-user local receiver repeat3

2026-05-22에 같은 Docker Compose app-1/app-2 환경에서 단일 방 50명 receiver matrix를 3회 반복했습니다.
이 실행은 50-user local scenario evidence이며, 500/1,000 session benchmark나 운영 성능 claim으로 사용하지 않습니다.

| run | accepted sends | expected | unique | missing | duplicate | completeness | p50 | p95 | p99 | max |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 100 | 4,900 | 4,900 | 0 | 0 | 100% | 18ms | 38ms | 127ms | 229ms |
| 2 | 100 | 4,900 | 4,900 | 0 | 0 | 100% | 16ms | 23ms | 36ms | 47ms |
| 3 | 100 | 4,900 | 4,900 | 0 | 0 | 100% | 16ms | 24ms | 27ms | 32ms |

요약 JSON은
[`docs/evidence/receiver-matrix-50users-repeat3-20260522-summary.json`](evidence/receiver-matrix-50users-repeat3-20260522-summary.json)에
보존했습니다. raw artifact root는 `artifacts/ws/20260522T114435Z-receiver-matrix-50users-repeat3`이며,
`artifacts/`는 local generated artifact로 git에 포함하지 않습니다.

세 번 모두 expected 4,900 / unique 4,900 / missing 0 / duplicate 0 / completeness 100%였고, p95 범위는
23-38ms입니다. 다만 이 결과도 local Docker Compose 반복 실행이므로 mixed traffic latency, production
performance claim으로 확장하지 않습니다.

### 5-2-2. 500-user local receiver repeat3

2026-05-22에 같은 Docker Compose app-1/app-2 환경에서 단일 방 500명 receiver matrix를 3회 반복했습니다.
이 실행은 더 큰 단일 hot room에서 fan-out과 receiver matrix 계산 경로를 확인한 local scenario evidence이며,
운영 성능 claim으로 사용하지 않습니다.

| 항목 | 값 |
| --- | --- |
| 조건 | room users 500, senders 5, sender당 20 messages, send interval 100ms |
| status denominator | 각 run accepted 100, failed 0, statusless 0 |
| matrix 검산 | 각 run expected 49,900, unique delivery 49,900, missing 0, duplicate 0, unexpected 0 |
| latency snapshot | p50 28-29ms, p95 37-47ms, p99 46-233ms |
| sender-local order diagnostic | 311 / 473 / 0 |
| sanitized summary | `docs/evidence/receiver-matrix-500users-20260522-summary.json` |

요약 문서는
[`docs/evidence/RECEIVER_MATRIX_500USERS_2026-05-22.md`](evidence/RECEIVER_MATRIX_500USERS_2026-05-22.md)에
보존했습니다. raw artifact roots는 `artifacts/ws/20260522T122502Z-receiver-matrix-500users-run1`,
`artifacts/ws/20260522T124030Z-receiver-matrix-500users-run2`,
`artifacts/ws/20260522T124126Z-receiver-matrix-500users-run3`이며,
`artifacts/`는 local generated artifact로 git에 포함하지 않습니다.

이 결과는 local repeat3 실행이므로 p95 37-47ms와 completeness 100%를 운영 성능 claim으로 확장하지 않습니다.
`senderLocalOutOfOrderCount`는 sender-local diagnostic이며 persisted DB id 기반 room-global ordering 성능 지표가 아닙니다.

### 5-2-3. 1,000-user local receiver repeat3

2026-05-23에 같은 Docker Compose app-1/app-2 환경에서 단일 방 1,000명 receiver matrix를 3회 반복했습니다.
이 실행은 1,000 local WebSocket session에서 fan-out, receiver matrix, persisted DB `messageId` 기반
room-global ordering diagnostic을 확인한 local scenario evidence이며, mixed traffic 또는 운영 성능 claim으로
사용하지 않습니다.

| run | accepted sends | persisted sends | expected | unique | missing | duplicate | completeness | p50 | p95 | p99 | max | room-global out-of-order |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 100 | 100 | 99,900 | 99,900 | 0 | 0 | 100% | 30ms | 45ms | 75ms | 98ms | 0 |
| 2 | 100 | 100 | 99,900 | 99,900 | 0 | 0 | 100% | 31ms | 50ms | 75ms | 90ms | 0 |
| 3 | 100 | 100 | 99,900 | 99,900 | 0 | 0 | 100% | 30ms | 45ms | 58ms | 70ms | 0 |

요약 JSON은
[`docs/evidence/receiver-matrix-1000users-repeat3-20260523-summary.json`](evidence/receiver-matrix-1000users-repeat3-20260523-summary.json)에
보존했습니다. 요약 문서는
[`docs/evidence/RECEIVER_MATRIX_1000USERS_REPEAT3_2026-05-23.md`](evidence/RECEIVER_MATRIX_1000USERS_REPEAT3_2026-05-23.md)에
분리했습니다. raw artifact root는 `artifacts/ws/20260523T005158Z-receiver-matrix-1000users-repeat3`이며,
`artifacts/`는 local generated artifact로 git에 포함하지 않습니다.

세 번 모두 expected 99,900 / unique 99,900 / missing 0 / duplicate 0 / completeness 100%였고, p95 범위는
45-50ms입니다. room-global ordering은 persisted DB `messageId`를 raw send/receive rows에 노출한 뒤
receiver + room별 단조 증가 위반을 계산했으며, 세 번 모두 `outOfOrderCount 0`입니다. 이 결과도 local
Docker Compose 반복 실행이므로 production p95, 장시간 soak, mixed traffic benchmark로 확장하지 않습니다.

같은 runner로 sender당 20 messages를 50ms 간격으로 보낸 smoke에서는 기본 SEND rate limit을 넘길 수
있어 expected 360건 중 170건만 unique delivery로 관측했습니다. 당시 runner는 ACK/NACK/status 로그가
없어 rate-limited send를 expected delivery 분모에서 제외하지 못했습니다. 현재 runner는 `status.jsonl`을
남기므로 후속 실행에서는 accepted/persisted/statusless 분모를 함께 확인해야 하며, 과거 missing count는
fan-out 손실로 해석하지 않습니다.

### 5-3. 2026-05-22 receipt-barrier 재실행 메모

이전 runner에 모든 구독의 STOMP receipt를 요구하는 barrier를 추가한 뒤 low-rate receiver matrix를 다시 시도했습니다.
Docker Compose app-1/app-2 환경은 기동됐지만, 두 실행 모두 send window 진입 전 `SUBSCRIBE` receipt 대기에서
timeout이 발생했습니다.

```txt
10 users, 2 senders, sender당 20 messages, subscribe receipt timeout 5s:
Timed out waiting for SUBSCRIBE receipts for userId=63

6 users, 2 senders, sender당 10 messages, subscribe receipt timeout 15s:
Timed out waiting for SUBSCRIBE receipts for userId=73
```

이 재실행은 delivery completeness나 send-to-receive latency 근거로 사용하지 않습니다. 후속 runner는
기본 barrier를 `CONNECTED` + settle delay로 낮추고, room/status receipt는 진단값으로 기록합니다. 같은
실패를 재현해야 할 때는 `--require-room-receipts true`와 `--require-status-receipts true`를 사용합니다.
현재 runner는 barrier 실패 시에도 `members.jsonl`, `send.jsonl`, `receive.jsonl`, `status.jsonl`,
`failure.json`을 남기므로, 실패 실행은 측정 근거가 아니라 barrier 디버깅 artifact로만 해석합니다.
`failure.json`의 `readiness` 배열은 사용자별 `CONNECTED` 수신 여부, required/optional `SUBSCRIBE`
receipt 수, pending receipt의 destination을 기록합니다. 이 값은 "어느 구독이 준비되지 않았는가"를
찾기 위한 진단 정보이며 delivery completeness 분모나 latency 계산에 사용하지 않습니다.

### 5-4. 2026-05-22 CONNECT + settle 재실행 메모

Spring simple broker 환경에서 `SUBSCRIBE` receipt가 돌아오지 않는 점을 확인한 뒤, runner 기본 barrier를
`CONNECTED` 확인 + settle delay로 낮춰 다시 실행했습니다. 아래 실행은 작은 local smoke이며,
delivery completeness나 send-to-receive latency를 공개 benchmark로 승격하지 않습니다.

```txt
users 10, senders 2, sender당 5 messages, settle 500ms:
expected deliveries 90
actual unique deliveries 90
missing deliveries 0
accepted sends 10
statusless sends 0
```

최신 raw 산출물은 `artifacts/ws/20260522T075845Z-receiver-matrix-lowrate`에 생성되었습니다. 이 경로는
로컬 artifact이며, 요약은 `docs/evidence/RECEIVER_MATRIX_SMOKE_2026-05-22.md`에 보존합니다.

### 5-5. 2026-05-22 Mixed Chat Smoke

`k6/mixed-chat-test.js`를 `SMOKE=1`, 1 VU 조건으로 local 단일 인스턴스에서 실행했습니다. 이 실행은
REST 조회, WebSocket 연결, 메시지 전송, ACK 수신, 발신자 self echo 관측, 읽음 처리 API 호출 경로가
함께 깨지지 않는지 확인하는 smoke입니다.

```txt
checks: 37 succeeded / 0 failed
iterations: 9
ack success rate: 100%
nack rate: 0%
WebSocket connection success rate: 100%
mixed error rate: 0%
```

보존 위치는 `docs/evidence/MIXED_CHAT_SMOKE_2026-05-22.md`입니다. 이 결과는 발신자 self echo 기준
관측이므로 실제 receiver 기준 delivery completeness, send-to-receive latency, room-global ordering
benchmark로 사용하지 않습니다.

### 5-6. 2026-05-23 10-room / 50-user mixed HTTP probe repeat3

2026-05-23에 local JVM 단일 앱과 격리된 Docker Postgres/Redis/Kafka 환경에서 `--mixed-http-probes true`
를 켠 receiver matrix를 3회 반복했습니다. 이 실행은 REST room list / message history / read receipt probe를
같은 artifact에 남기면서 receiver 기준 send-to-receive latency와 delivery completeness를 검산한 local
scenario evidence입니다. HTTP probe는 WebSocket drain 이후 실행되며, receiver delivery denominator에는
포함하지 않습니다.

| run | accepted sends | persisted sends | expected | unique | missing | duplicate | completeness | p50 | p95 | p99 | max | HTTP failed | room-global out-of-order |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 100 | 100 | 4,900 | 4,900 | 0 | 0 | 100% | 13ms | 20ms | 39ms | 81ms | 0 / 30 | 0 |
| 2 | 100 | 100 | 4,900 | 4,900 | 0 | 0 | 100% | 13ms | 18ms | 20ms | 23ms | 0 / 30 | 0 |
| 3 | 100 | 100 | 4,900 | 4,900 | 0 | 0 | 100% | 13ms | 20ms | 26ms | 27ms | 0 / 30 | 0 |

요약 JSON은
[`docs/evidence/mixed-traffic-10rooms-50users-repeat3-20260523-summary.json`](evidence/mixed-traffic-10rooms-50users-repeat3-20260523-summary.json)에
보존했습니다. 요약 문서는
[`docs/evidence/MIXED_TRAFFIC_10ROOMS_50USERS_REPEAT3_2026-05-23.md`](evidence/MIXED_TRAFFIC_10ROOMS_50USERS_REPEAT3_2026-05-23.md)에
분리했습니다. raw artifact root는
`artifacts/ws/20260523T014727Z-mixed-traffic-10rooms-50users-repeat3`이며, `artifacts/`는 local generated
artifact로 git에 포함하지 않습니다.

세 run 모두 `node scripts/validate-delivery-evidence.mjs --artifact-dir <run-dir>`를 통과했습니다. 이 결과는
local 단일 앱 반복 실행이므로 multi-instance scale-out, 1,000-session mixed benchmark, production benchmark,
cache hit ratio로 확장하지 않습니다.

## 6. 권장 시나리오

| 시나리오 | 목적 | 상태 |
| --- | --- | --- |
| 1 room, 50 users | 단일 hot room fan-out | local repeat3 기록, public benchmark는 추가 측정 예정 |
| 10 rooms, room당 50 users | room 분산 traffic + mixed HTTP probe | local repeat3 기록, production benchmark는 추가 측정 예정 |
| 500 concurrent WebSocket sessions | 중간 규모 동시 연결 | local repeat3 기록, public benchmark는 추가 측정 예정 |
| 1,000 concurrent WebSocket sessions | 높은 동시 연결 | local repeat3 기록, public/mixed benchmark는 추가 측정 예정 |
| 10 msg/s | 낮은 전송률 baseline | 50-user local snapshot 후보, 반복 benchmark는 추가 측정 예정 |
| 50 msg/s | 일반 부하 | 추가 측정 예정 |
| 100 msg/s | burst에 가까운 부하 | 추가 측정 예정 |

## 7. 실행 전 체크리스트

- 기본 실행은 모든 receiver의 `CONNECTED` 확인과 settle delay 이후 send window를 시작한다. room/status
  `SUBSCRIBE` receipt는 진단값이며, strict 재현이 필요할 때만 `--require-room-receipts true` /
  `--require-status-receipts true`를 사용한다.
- rate limit 또는 ACK 미수신 메시지는 delivery completeness 분모에 넣지 않고 `statusless` 또는
  확인 가능한 경우 `rate_limited`로 분리한다.
- NACK 메시지는 expected delivery에서 제외하고 NACK rate로 별도 보고한다.
- 테스트 종료 전 drain 시간을 두어 비동기 Kafka -> Redis Pub/Sub -> WebSocket fan-out을 기다린다.
- 결과 표에는 `추가 측정 예정`, `시나리오 검증`, `측정 완료` 중 하나를 명시한다.

## 7-1. 근거 승격 체크리스트

artifact를 `docs/PERF_RESULT.md` 또는 portfolio claim으로 옮기기 전에 아래를 모두 확인한다.

- `node scripts/validate-delivery-evidence.mjs --artifact-dir <artifact-dir>`가 통과한다.
- `manifest.json`이 options, environment, claimBoundary, expected sessions/rooms/messages를 기록한다.
- `summary.json`이 raw `members.jsonl`, `send.jsonl`, `receive.jsonl`, `status.jsonl`, `http.jsonl`에서 재생성된다.
- 각 expected room id가 `summary.byRoom`에 존재한다.
- mixed HTTP probe를 포함한 실행은 `mixedHttp.failedRequests === 0`이다.
- `statusless`, `failed`, `missing`, `duplicate`, `unexpected`가 있으면 성능 성공 claim이 아니라 진단 결과로만 쓴다.
- production/mixed benchmark는 해당 규모의 raw artifact, manifest, validator 통과 기록이 있을 때만
  `측정 완료`로 승격한다. 현재 local receiver matrix는 `시나리오 검증`으로 유지한다.

## 8. 결과 기록 템플릿

실행 후에만 `docs/PERF_RESULT.md`에 아래 형식으로 기록한다.

| 항목 | 값 | 조건 |
| --- | --- | --- |
| scenario | 추가 측정 예정 | 실행 후 기록 |
| concurrent sessions | 추가 측정 예정 | 실행 후 기록 |
| room count / users per room | 추가 측정 예정 | 실행 후 기록 |
| message rate | 추가 측정 예정 | 실행 후 기록 |
| send-to-receive p50 | 추가 측정 예정 | 실행 후 기록 |
| send-to-receive p95 | 추가 측정 예정 | 실행 후 기록 |
| send-to-receive p99 | 추가 측정 예정 | 실행 후 기록 |
| expected deliveries | 추가 측정 예정 | 실행 후 기록 |
| actual unique deliveries | 추가 측정 예정 | 실행 후 기록 |
| missing deliveries | 추가 측정 예정 | 실행 후 기록 |
| duplicate deliveries | 추가 측정 예정 | 실행 후 기록 |
| delivery completeness | 추가 측정 예정 | 실행 후 기록 |
| sender-local order diagnostic | 추가 측정 예정 | 실행 후 기록 |
| room-global ordering diagnostic | 추가 측정 예정 | persisted DB message id가 있을 때만 기록 |
