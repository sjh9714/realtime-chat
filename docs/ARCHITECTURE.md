# Realtime Chat Architecture

이 문서는 서버와 저장소를 한 장에 모두 나열하지 않습니다. 사용자가 체감하는 메시지 생명주기와 실패 복구
경계만 순서대로 설명합니다.

## 1. 메시지가 저장되기까지

| 단계 | 구성 요소 | 결과 | 클라이언트 상태 |
| --- | --- | --- | --- |
| 1. 작성 | React client | optimistic row와 clientMessageId 생성 | <code>SENDING</code> |
| 2. 전송 | STOMP controller | JWT, room membership, payload 검증 | <code>SENDING</code> |
| 3. 접수 | Kafka producer | roomId key publish callback 성공 | <code>ACCEPTED</code> |
| 4. 저장 | Kafka consumer + PostgreSQL | message row와 unread 상태 commit | 아직 상대 broadcast 없음 |
| 5. 전달 | Redis Pub/Sub | commit된 DB ID payload를 room topic으로 fan-out | receiver에 표시 |
| 6. 확정 | user persisted queue | 발신자의 optimistic row를 DB ID로 교체 | <code>PERSISTED</code> |
| 7. 완료 | Kafka acknowledgment | 저장과 필요한 publish가 끝난 record ACK | 변경 없음 |

<code>ACCEPTED</code>는 Kafka publish 성공이고, <code>PERSISTED</code>는 DB 저장 완료 또는 기존 idempotent
row 확인입니다. 어느 상태도 모든 상대방의 수신 완료를 뜻하지 않습니다.

## 전환 전후

| 이전 구조 | 현재 구조 |
| --- | --- |
| persistence와 broadcast consumer가 독립적으로 event 처리 | 한 consumer가 저장 뒤 broadcast |
| broadcast가 DB commit보다 먼저 끝날 수 있음 | commit된 payload만 room channel에 publish |
| 저장 실패 뒤 상대 화면에 유령 메시지가 남을 수 있음 | 저장 실패 시 receiver publish에 도달하지 않음 |
| broadcast 성공 여부와 Kafka ACK 경계가 분리됨 | Redis publish 실패 시 ACK를 보류하고 redelivery |

현재 구조는 receiver delivery를 durable store로 사용하지 않습니다. PostgreSQL message ID가 재연결과
중복 제거의 기준입니다.

## 2. 재시도와 redelivery

### 클라이언트 retry

1. 클라이언트는 최초 전송에서 만든 clientMessageId를 유지합니다.
2. 서버는 senderId와 clientMessageId로 기존 row를 찾습니다.
3. 기존 row가 있으면 발신자에게 같은 DB ID의 PERSISTED를 다시 보냅니다.
4. receiver room에는 같은 메시지를 다시 broadcast하지 않습니다.

### Kafka redelivery

1. consumer가 messageKey로 기존 DB row를 확인합니다.
2. 이미 commit된 unread·cache 부수 효과를 반복하지 않습니다.
3. 이전에 실패한 Redis fan-out을 다시 시도합니다.
4. publish가 끝난 뒤 Kafka record를 ACK합니다.

이 두 중복 경계는 다릅니다. clientMessageId는 사용자의 재전송을, messageKey는 Kafka record의 재전달을
구분합니다.

## 3. 오프라인과 재연결

Redis Pub/Sub는 online fan-out이고, Redis Presence는 session TTL 기반 임시 상태입니다. 둘 다 누락
메시지를 복구하는 진실 소스가 아닙니다.

| 단계 | 클라이언트 | 서버 |
| --- | --- | --- |
| 1. 연결 끊김 | 마지막 persisted DB ID 보존 | 메시지를 계속 PostgreSQL에 저장 |
| 2. 재연결 | room topic을 다시 구독 | JWT와 room membership 재검증 |
| 3. sync | afterMessageId를 전송 | 이후 메시지를 ID 오름차순으로 반환 |
| 4. pagination | 마지막 응답 ID를 다음 cursor로 사용 | limit을 검증하고 hasMore 반환 |
| 5. 병합 | live와 sync payload를 DB ID로 dedupe | 다른 room cursor와 비멤버 요청 거부 |

## 4. 권한 경계

| 진입점 | 검증 |
| --- | --- |
| STOMP CONNECT | JWT를 검증하고 principal에 userId 바인딩 |
| STOMP SUBSCRIBE | room topic의 membership 확인 |
| STOMP SEND | destination, payload, room membership 재검증 |
| Message history / sync REST | 요청 사용자가 해당 room member인지 확인 |
| Presence topic | room member만 접근 |

인증된 사용자도 roomId를 추측해 다른 방을 읽거나 구독할 수 없습니다.

## 5. ordering 범위

Kafka publish key는 roomId입니다. 같은 방의 메시지는 같은 partition에서 offset 순서를 따르지만, 서로 다른
방 사이의 전역 순서는 정의하지 않습니다. 클라이언트는 DB message ID를 sync cursor와 dedupe key로
사용하며, 이를 wall-clock 전체 순서로 해석하지 않습니다.

## 검증 연결

- [두 app instance의 전달·retry·오프라인·장애 복구 E2E](../web/e2e/chat-flow.spec.ts)
- [commit 뒤 publish와 Redis 실패](../src/test/java/com/realtime/chat/MessagePersistenceConsumerPublishTest.java)
- [clientMessageId와 Kafka redelivery idempotency](../src/test/java/com/realtime/chat/MessagePersistenceServiceTest.java)
- [DB persisted ACK](../src/test/java/com/realtime/chat/WebSocketPersistedAckIntegrationTest.java)
- [reconnect sync API](../src/test/java/com/realtime/chat/MessageIntegrationTest.java)
- [비멤버 SUBSCRIBE 차단](../src/test/java/com/realtime/chat/WebSocketSubscribeAuthorizationIntegrationTest.java)
- [room partition ordering](../src/test/java/com/realtime/chat/MessageOrderingIntegrationTest.java)

## Claim boundary

- 현재 성능 주장: 없음.
- historical unpinned archive의 수치는 현재 room-list와 persistence pipeline의 evidence가 아닙니다.
- local E2E는 실패 순서와 복구 결과를 검증하며 production latency, delivery completeness, SLO를 보장하지
  않습니다.
- DLT와 runbook은 제한된 재현 utility이며 production 운영 체계를 의미하지 않습니다.

측정 archive는 [PERF_RESULT.md](PERF_RESULT.md)와 [WEBSOCKET_MEASUREMENT.md](WEBSOCKET_MEASUREMENT.md),
실행 명령과 근거는 [TESTING.md](TESTING.md)에 분리합니다.
