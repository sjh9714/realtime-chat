# Realtime Chat Architecture

이 문서는 README의 전체 아키텍처 다이어그램을 보조하는 설명입니다. 목적은 구현된 핵심 흐름과 검증 대상 경계를 빠르게 읽히게 하는 것이며, 운영 배포 토폴로지나 production SLO를 주장하지 않습니다.

## 전체 구조

![Realtime Chat 전체 아키텍처](assets/architecture/overall-architecture.svg)

README와 이 문서에서는 [`overall-architecture.svg`](assets/architecture/overall-architecture.svg)를 기준으로 노출합니다.
[`overall-architecture.drawio`](assets/architecture/overall-architecture.drawio)는 편집 참고 자산으로 보관합니다.

## 핵심 흐름

| 단계 | 구성 요소 | 역할 | 검증 경계 |
|---|---|---|---|
| WebSocket 연결 | Client, WebSocket Endpoint | STOMP `CONNECT`, `SUBSCRIBE`, `SEND` 진입점 | 연결과 인증 경계 |
| 구독/전송 인가 | STOMP Auth | JWT 인증과 room membership 검증 | 비멤버 구독 거부, 전송 전 room member 재검증 |
| 메시지 발행 | Message Producer, Kafka Topic by roomId | `roomId` key로 Kafka `chat.messages`에 publish | ACK/NACK는 Kafka publish accepted/failed 기준 |
| 메시지 저장 | Kafka Consumer, PostgreSQL Message / Room / Read State | consumer가 메시지를 저장하고 persisted 상태를 알림 | PostgreSQL row가 reconnect recovery truth |
| 수신자 전달 | Kafka Consumer, Fan-out | broadcast path가 room topic으로 receiver delivery 수행 | receiver runner 관측은 시나리오 검증 경계 |
| 재연결 보정 | Reconnect Sync API, PostgreSQL | `lastReceivedMessageId` 이후 메시지를 조회 | Redis Pub/Sub 누락 가능성 보정 |
| 임시 상태 | Redis Presence | session TTL 기반 online 상태 | ephemeral state이며 복구 진실 소스가 아님 |
| 캐시/제한 | Redis Cache / Rate Limit | room list cache와 global SEND fixed-window 제한 | cache hit rate는 추가 측정 예정 |
| 장애 격리 | DLT | consumer 실패 메시지 격리와 manual replay 대상 | replay utility 검증, 운영 도구 claim 아님 |

## 설계 판단

| 판단 | 이유 | 주의할 점 |
|---|---|---|
| WebSocket 연결, Kafka publish, DB persisted, receiver delivery를 분리 | 클라이언트가 어떤 단계까지 성공했는지 오해하지 않게 하기 위함 | ACK는 상대방 수신 완료가 아니라 Kafka publish 결과 |
| Kafka key를 `roomId`로 사용 | 같은 room 메시지를 같은 partition 순서로 처리하기 위함 | 서로 다른 room 간 전역 순서는 보장하지 않음 |
| PostgreSQL을 reconnect recovery truth로 둠 | Redis Pub/Sub는 best-effort이고 presence는 TTL 기반 임시 상태이기 때문 | 재연결 클라이언트는 sync API를 호출해야 함 |
| Redis Presence를 ephemeral state로 취급 | 다중 세션 online/offline 판단은 빠른 TTL 상태가 적합하기 때문 | 메시지 복구나 delivery completeness의 진실 소스로 쓰지 않음 |
| DLT를 별도 장애 격리 경계로 둠 | consumer 실패가 정상 메시지 흐름을 막지 않게 하기 위함 | production replay 운영 절차와 감사 로그는 별도 과제 |

## Claim Boundary

- `1,000 session send-to-receive latency | benchmark 미측정`
- `1,000 session delivery completeness | benchmark 미측정`
- `mixed traffic p95 latency | benchmark 미측정`
- `mixed traffic cache hit rate는 추가 측정 예정`

이 다이어그램은 구현된 핵심 흐름과 검증 대상 경계를 설명하기 위한 단순화된 구조도이며, 운영 배포 토폴로지나 production SLO를 주장하지 않습니다.
