# Realtime Chat

Kafka와 Redis Pub/Sub 기반의 다중 인스턴스 실시간 채팅 시스템에서
**구독 권한, Kafka publish ACK/NACK, room 단위 메시지 순서, 읽음 정합성,
DLT 격리와 수동 replay utility**를 검증한 Spring Boot 백엔드 프로젝트입니다.

이 프로젝트는 단순히 WebSocket 채팅 기능을 구현하는 것이 아니라,
실시간 메시징 시스템에서 쉽게 깨지는 **권한, 순서, 중복, 장애 복구,
presence, cache invalidation** 문제를 코드와 테스트로 검증하는 데 초점을 둡니다.

![CI](https://github.com/sjh9714/realtime-chat/actions/workflows/ci.yml/badge.svg)

---

## 30초 요약

이 프로젝트는 Java/Spring 기반 실시간 채팅 백엔드에서 **권한, 메시지 상태 경계,
재연결 보정, room 단위 ordering, 장애 격리**를 코드와 테스트로 검증한 사례입니다.

- 만든 것: Spring Boot 백엔드와 React/Vite 실시간 채팅 클라이언트
- 설계 포인트: `CONNECT` 인증과 `SUBSCRIBE` 인가 분리
- 상태 경계: Kafka publish ACK/NACK와 DB PERSISTED ACK 분리
- 복구 경계: PostgreSQL commit 이후 broadcast, 재연결 sync, client retry 멱등성
- 현재 성능 주장: 없음. 현재 room-list와 persistence pipeline 기준 재측정 전까지 보류

로컬 제품 데모는 `.env.example`을 `.env`로 복사해 secret을 교체한 뒤 실행합니다.

```bash
docker compose -f docker-compose.demo.yml up --build --wait
open http://localhost:14173
```

데모는 두 Spring Boot app을 nginx gateway 뒤에 띄우며 Alice/Bob 계정을 자동 생성합니다. 첫 화면의
`Alice 데모 계정으로 바로 시작` 버튼으로 별도 입력 없이 접속할 수 있습니다.
공개 데모에는 장애 주입과 app instance 진단 API가 포함되지 않으며, `/api/demo/**`는 `404`를 반환합니다.

브라우저 클라이언트는 TanStack Query로 REST 상태를, Zustand로 낙관적 메시지 상태를 관리합니다.
메시지는 `SENDING -> ACCEPTED -> PERSISTED`로 전환되고, 연결 복구 시 마지막 DB message id 이후를 다시 동기화합니다.

ACK / PERSISTED / RECEIVED 의미:

- ACK: Kafka broker가 publish 요청을 accepted 했다는 뜻입니다.
- PERSISTED: DB 저장 완료 또는 기존 idempotent row 확인을 뜻합니다.
- RECEIVED: receiver runner가 room topic MESSAGE를 관측한 기록이며 production delivery claim이 아닙니다.

---

## 전체 아키텍처

![Realtime Chat 전체 아키텍처](docs/assets/architecture/overall-architecture.svg)

Client는 WebSocket STOMP 연결로 메시지를 보내고, Spring Boot app은 인증/인가 후 Kafka에 `roomId` key로 publish합니다.
Kafka consumer는 PostgreSQL commit을 완료한 뒤 같은 persisted payload를 Redis room fan-out과 발신자 queue에 발행합니다.
Redis Presence는 TTL 기반 임시 상태이며, PostgreSQL의 message / room / read state가 재연결 복구의 진실 소스입니다.

### 핵심 설계 판단

| 판단 | 이유 | 경계 |
|---|---|---|
| WebSocket 연결, Kafka publish, DB persisted, receiver delivery 분리 | ACK/PERSISTED/RECEIVED가 서로 다른 성공 단계를 의미하기 때문 | ACK는 상대방 수신 완료가 아니라 Kafka publish 결과 |
| Kafka topic은 `roomId` key 사용 | 같은 room 메시지를 같은 partition 순서로 처리하기 위함 | 서로 다른 room 간 전역 순서는 보장하지 않음 |
| PostgreSQL을 recovery truth로 사용 | Redis Pub/Sub와 Presence는 복구용 durable store가 아니기 때문 | 재연결 시 `lastReceivedMessageId` 이후를 sync API로 조회 |
| Redis는 Presence / Cache / persisted fan-out / Rate Limit에 사용 | 빠른 TTL 상태와 multi-instance fan-out에 적합하기 때문 | durable recovery source는 PostgreSQL |
| DLT로 consumer 실패 격리 | 실패 메시지가 정상 흐름을 막지 않도록 분리하기 위함 | replay 운영 절차와 감사 로그는 별도 과제 |

이 다이어그램은 구현된 핵심 흐름과 검증 대상 경계를 설명하기 위한 단순화된 구조도이며, 운영 배포 토폴로지나 production SLO를 주장하지 않습니다.

상세 설명은 [아키텍처 문서](docs/ARCHITECTURE.md)에 분리했습니다.

---

## 핵심 문제

실시간 채팅 백엔드는 WebSocket 연결만으로 완성되지 않습니다.
다중 서버 환경에서는 메시지가 여러 인스턴스, Kafka, Redis Pub/Sub,
DB 저장 경로를 거치기 때문에 아래 문제가 함께 해결되어야 합니다.

| 문제 | 구현한 대응 |
|---|---|
| 유효한 JWT 사용자가 `roomId`만 알고 다른 방을 구독할 수 있는가 | STOMP `SUBSCRIBE /topic/room.{roomId}` 시 room membership 검증 |
| 메시지 전송 요청이 Kafka publish에 성공했는지 클라이언트가 알 수 있는가 | `/user/queue/messages/ack`, `/user/queue/messages/error` ACK/NACK 응답 |
| 다중 app instance에서 같은 사용자가 SEND 제한을 우회할 수 있는가 | Redis Lua `INCR + PEXPIRE` 원자 연산으로 user-level global SEND rate limit 적용 |
| WebSocket 재연결 중 Redis Pub/Sub fan-out을 놓칠 수 있는가 | `lastReceivedMessageId` 이후 메시지를 조회하는 reconnect sync API 제공 |
| 같은 채팅방 메시지가 순서대로 처리되는가 | Kafka key를 `roomId`로 사용하고, 같은 room 내 partition offset 순서를 검증 |
| consumer 실패 메시지를 격리하고 재처리할 수 있는가 | `chat.messages.dlt` 격리와 `DltReplayService` manual replay utility |
| 읽음 수가 발신자 본인 메시지나 참여 전 메시지까지 포함하지 않는가 | `senderId != userId`, `joinedAt`, `lastReadMessageId` 기준으로 unread count 재계산 |
| 한 사용자가 여러 세션으로 접속했을 때 일부 세션 종료로 offline 처리되는가 | `userId + sessionId` 단위 Redis TTL presence |
| 메시지 저장 시 관계없는 사용자의 채팅방 목록 cache까지 삭제되는가 | 해당 room member의 `rooms::{userId}` cache만 evict |

---

## Evidence Matrix

아래 표는 현재 코드에서 자동화 테스트로 확인하는 정합성 계약입니다. 처리량이나 지연 시간 주장이 아닙니다.

| 항목 | 검증하는 계약 | 근거 |
|---|---|---|
| STOMP inbound authorization | 비멤버 구독과 client의 broker destination 직접 SEND 거부 | Unit + 실제 STOMP integration 테스트 |
| Redis global SEND rate limit | user-level fixed-window 제한과 Redis 장애 시 fail-closed | Unit 테스트 |
| Kafka publish ACK/NACK | user destination으로 publish 결과 전달 | Kafka publish callback 테스트 |
| commit-before-broadcast | PostgreSQL commit 뒤에만 room payload와 PERSISTED 알림 발행 | Unit + integration 테스트 |
| reconnect sync | 마지막으로 받은 DB message id 이후의 누락 메시지 조회 | Integration + browser E2E |
| client retry idempotency | `(senderId, clientMessageId)` 기준 중복 저장과 receiver 재전달 방지 | Unit + browser E2E |
| Redis publish recovery | commit된 row를 Kafka redelivery에서 재사용해 fan-out 재시도 | Two-node browser E2E |
| DLT manual replay | replay 시 `messageKey` 기준 중복 저장 방지 | Testcontainers integration 테스트 |
| room 단위 ordering | 동일 room의 Kafka partition / offset 순서 | Integration 테스트, 전역 순서 보장 아님 |
| read receipt | sender와 참여 전 메시지를 unread 계산에서 제외 | Service + integration 테스트 |
| multi-session presence | 마지막 active session 종료 시에만 offline 전환 | Redis integration 테스트 |
| selective cache eviction | commit 뒤 room member의 cache만 best-effort evict | Unit + cache failure integration 테스트 |

과거 부하 측정 문서는 실행 방법과 이전 실험 기록을 보존하기 위한 archive입니다. 현재 room-list와
persistence pipeline을 대상으로 한 commit-pinned 결과가 아니므로 현재 코드의 공개 성능 근거로 사용하지 않습니다.
성능 수치는 같은 commit에서 다시 측정하고 artifact와 환경을 고정한 뒤에만 README로 올립니다.

---

## 주요 설계 결정

### 1. STOMP `CONNECT` 인증과 `SUBSCRIBE` 인가를 분리

`CONNECT`에서 JWT를 검증하는 것만으로는 충분하지 않습니다. 인증된 사용자가 `roomId`를 추측해 `/topic/room.{roomId}`를 구독할 수 있기 때문입니다.

그래서 inbound channel에 두 단계 검증을 둡니다.

| 단계 | 담당 | 목적 |
|---|---|---|
| `CONNECT` | `WebSocketAuthInterceptor` | JWT 검증, STOMP Principal에 `userId` 바인딩 |
| `SUBSCRIBE` | `WebSocketAuthorizationInterceptor` | room message/presence topic 구독 시 room member 검증 |
| `SEND` | `ChatMessageController` | 메시지 전송 시 room member 재검증 |

비멤버 구독과 malformed room topic을 거부합니다. 전역 `/topic/presence`는 사용자 상태 노출을 막기 위해 폐기하고 `/topic/room.{roomId}.presence`만 허용합니다.

---

### 2. Redis 기반 global SEND rate limit

`RateLimitInterceptor`는 `/app/chat.send` frame에만 user-level rate limit을 적용합니다. Lua script가 `INCR`와 최초 `PEXPIRE`를 한 원자 연산으로 실행합니다.

```text
rate:ws:send:user:{userId}:{epochSecond}
```

| 항목 | 정책 |
|---|---|
| 대상 | STOMP `SEND /app/chat.send` frame |
| 기본 제한 | `chat.rate-limit.messages-per-second: 10` |
| Redis key TTL | 2초 |
| Redis 장애 | fail-closed, SEND 거부 |
| 제외 | `CONNECT`, `SUBSCRIBE` |

fixed-window 방식이므로 초 경계에서 순간 burst가 생길 수 있습니다. 더 엄밀한 smoothing이 필요하면 token bucket 또는 sliding window Lua script가 별도 개선 과제입니다.

---

### 3. Kafka publish ACK/NACK와 DB persisted ACK

`/app/chat.send`는 메시지를 직접 DB에 저장하지 않고 Kafka `chat.messages` topic에 publish합니다.

```text
Client SEND /app/chat.send
  -> room member check
  -> KafkaTemplate.send(chat.messages, key = roomId, event)
  -> success callback: /user/queue/messages/ack
  -> failure callback: /user/queue/messages/error
  -> persistence consumer save success: /user/queue/messages/persisted
```

ACK payload 예시:

```json
{
  "clientMessageId": "9b75d8e9-5f73-4f6d-8f1a-3b1c0d7e8d10",
  "roomId": 1,
  "status": "ACCEPTED",
  "acceptedAt": "2026-05-11T10:15:30"
}
```

NACK payload 예시:

```json
{
  "clientMessageId": "9b75d8e9-5f73-4f6d-8f1a-3b1c0d7e8d10",
  "roomId": 1,
  "status": "FAILED",
  "reason": "Kafka publish failed"
}
```

PERSISTED payload 예시:

```json
{
  "clientMessageId": "9b75d8e9-5f73-4f6d-8f1a-3b1c0d7e8d10",
  "messageKey": "3f430c7b-4ed9-4c52-8ef3-9503d19a65f1",
  "messageId": 100,
  "roomId": 1,
  "status": "PERSISTED",
  "persistedAt": "2026-05-11T10:15:31"
}
```

> ACCEPTED ACK는 Kafka broker가 publish 요청을 accepted 했다는 뜻입니다.
> PERSISTED ACK는 DB commit 완료 또는 기존 idempotent row 확인 뒤 persisted event를 발행한 결과입니다.
> 상대 클라이언트 수신 완료나 읽음 완료를 의미하지 않습니다.

`clientMessageId`는 ACK/NACK correlation과 클라이언트 재시도 멱등성에 사용합니다.
DB는 `(senderId, clientMessageId)` unique constraint로 같은 발신자의 같은 클라이언트 메시지가 중복 저장되지 않게 막습니다.
`messageKey`는 Kafka event/message identity이며 DLT replay와 Kafka-level duplication에 대한 멱등성 기준으로 유지합니다.

---

### 4. room 단위 Kafka ordering

Kafka의 전역 순서를 보장하지 않습니다. 이 프로젝트에서 검증한 순서 범위는 **동일 room 내 partition ordering**입니다.

```text
producer key = roomId
  -> 같은 roomId는 같은 Kafka partition
  -> 같은 partition 안에서는 offset 순서 검증. Claim boundary: 동일 room partition 범위로만 해석
  -> consumer 저장 시 kafkaPartition, kafkaOffset 기록
  -> DB 조회 시 offset 순서 검증
```

| 검증 범위 | 설명 |
|---|---|
| 같은 room 안의 메시지 순서 | `roomId` key 기반 partition ordering |
| 서로 다른 room 간 순서 | 보장하지 않음 |
| receiver 전달과 장애 복구 | two-node Playwright 시나리오로 정합성만 검증 |
| 처리량과 지연 시간 | 현재 commit 기준 재측정 전까지 공개하지 않음 |

---

### 5. DLT 격리와 manual replay utility

Kafka consumer 실패 메시지는 DLT로 격리합니다. `DltReplayService`는 `chat.messages.dlt`에 쌓인 `ChatMessageEvent`를 원래 topic인 `chat.messages`로 다시 발행하는 내부 utility입니다.

```text
consumer failure
  -> retry
  -> chat.messages.dlt
  -> 원인 제거
  -> DltReplayService manual replay
  -> chat.messages
  -> consumer 재처리
```

Replay 중복 저장은 `messageKey` unique constraint와 consumer의 `existsByMessageKey` 체크로 방지합니다.
클라이언트 재시도 중복 저장은 별도로 `(senderId, clientMessageId)` unique constraint로 방지합니다.

> 이 기능은 자동 복구 시스템이 아닙니다. 운영 환경에서는 replay 권한 제어, 감사 로그, replay 대상 필터링, 재처리 결과 추적이 추가로 필요합니다.

---

### 6. read receipt 정합성

읽음 처리는 단순히 `lastReadMessageId` 이후 메시지를 세지 않습니다. 아래 조건을 함께 적용합니다.

```text
roomId가 동일해야 함
message.id > lastReadMessageId
message.senderId != userId
message.createdAt >= member.joinedAt
```

또한 `lastReadMessageId`가 해당 room의 메시지인지 확인하고, 사용자가 방에 참여하기 전에 생성된 메시지는 읽음 기준으로 사용할 수 없도록 검증합니다.

중복 read receipt가 들어와도 기존 `lastReadMessageId`보다 크지 않으면 상태를 되돌리지 않습니다.

---

### 7. session 단위 presence

Presence는 user 단일 key가 아니라 session 단위로 관리합니다.

```text
user:presence:{userId}:session:{sessionId}  TTL 60s
user:presence:{userId}:sessions            Redis Set
```

동작 방식:

| 이벤트 | 처리 |
|---|---|
| WebSocket connect | session key 생성, session set에 추가 |
| heartbeat | session key TTL 갱신 |
| disconnect | 해당 session 제거 |
| 마지막 session disconnect | offline event 발행 |
| 일부 session만 disconnect | online 유지 |

클라이언트는 `/app/presence.heartbeat`를 TTL보다 짧은 주기로 보내야 합니다.

---

### 8. Cache Aside selective eviction

채팅방 목록은 사용자별로 cache합니다.

```java
@Cacheable(value = "rooms", key = "#userId")
```

메시지가 저장되면 전체 `rooms` cache를 clear하지 않고, 해당 room 멤버의 cache만 evict합니다.

| 이벤트 | cache 무효화 범위 |
|---|---|
| 메시지 저장 | 해당 room 멤버의 `rooms::{userId}` |
| 읽음 처리 | 읽음 처리한 user의 `rooms::{userId}` |
| 방 생성 / 참여 | 현재 구현은 기존 정책 유지 |

---

## 성능 측정 상태

현재 README에는 처리량, latency, 동시 접속 수 같은 성능 수치를 게시하지 않습니다. room-list 조회와
persistence pipeline이 바뀐 뒤 같은 commit을 기준으로 다시 측정하지 않았기 때문입니다.

[`docs/PERF_RESULT.md`](docs/PERF_RESULT.md)와
[`docs/WEBSOCKET_MEASUREMENT.md`](docs/WEBSOCKET_MEASUREMENT.md)는 이전 실험의 실행 방법과 결과를
남긴 archive입니다. commit pin이 없는 historical record이며 현재 코드의 성능 evidence가 아닙니다.

다음 측정에서는 현재 commit, 환경, 명령, raw artifact를 함께 고정합니다. 그 전까지 자동화 테스트는
권한, commit 순서, 멱등성, cross-node 전달과 복구의 정합성만 뒷받침합니다.

---

## API 요약

### Auth

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/api/auth/signup` | 회원가입 |
| `POST` | `/api/auth/login` | 로그인 |

### Chat Room

| Method | Path | 설명 |
|---|---|---|
| `POST` | `/api/rooms/direct` | 1:1 채팅방 생성 |
| `POST` | `/api/rooms/group` | 그룹 채팅방 생성 |
| `POST` | `/api/rooms/{roomId}/join` | 그룹 채팅방 참여 |
| `GET` | `/api/rooms` | 내 채팅방 목록 |
| `GET` | `/api/rooms/{roomId}` | 채팅방 상세 |
| `GET` | `/api/rooms/{roomId}/messages` | 메시지 이력 |
| `GET` | `/api/rooms/{roomId}/messages/sync` | 재연결 후 누락 메시지 동기화 |
| `POST` | `/api/rooms/{roomId}/read` | 읽음 처리 |

### WebSocket / STOMP

WebSocket endpoint:

```text
/ws
```

| Client action | Destination |
|---|---|
| STOMP 연결 | `CONNECT /ws` |
| 메시지 전송 | `/app/chat.send` |
| 방 메시지 구독 | `/topic/room.{roomId}` |
| Presence heartbeat | `/app/presence.heartbeat` |
| Kafka publish ACK 구독 | `/user/queue/messages/ack` |
| Kafka publish NACK 구독 | `/user/queue/messages/error` |
| DB 저장 완료 ACK 구독 | `/user/queue/messages/persisted` |

메시지 전송 payload 예시:

```json
{
  "clientMessageId": "9b75d8e9-5f73-4f6d-8f1a-3b1c0d7e8d10",
  "roomId": 1,
  "content": "hello",
  "type": "TEXT"
}
```

읽음 처리 payload 예시:

```json
{
  "lastReadMessageId": 100
}
```

---

## 실행 방법

### 1. 전체 Docker Compose 실행

PostgreSQL, Redis, Kafka, Kafka UI, Prometheus, Grafana, app 2대를 함께 실행합니다.

```bash
docker compose up -d
```

서비스 포트:

| 서비스 | URL |
|---|---|
| App #1 | `http://localhost:8081` |
| App #2 | `http://localhost:8082` |
| Kafka UI | `http://localhost:8090` |
| Prometheus | `http://localhost:9090` |
| Grafana | `http://localhost:3000` |

Grafana 기본 계정:

```text
admin / admin
```

### 2. 인프라만 실행하고 애플리케이션 로컬 실행

```bash
docker compose up -d postgres redis kafka kafka-ui

./gradlew bootRun
```

로컬 실행 시 기본 app URL:

```text
http://localhost:8080
```

스키마는 Flyway migration으로 생성됩니다. 기존 `schema.sql` 기반 Docker volume을 쓰던 로컬 환경에서는 migration history가 없을 수 있으므로, 스키마 초기화 문제가 나면 아래처럼 volume을 비운 뒤 다시 실행합니다.

```bash
docker compose down -v
docker compose up -d
```

### 3. 테스트 실행

Testcontainers로 PostgreSQL, Kafka, Redis를 띄우므로 Docker가 실행 중이어야 합니다.

```bash
./gradlew test
./gradlew build
cd web
npm test
npm run build
```

### 4. 테스트 전용 two-node E2E

장애 주입 API와 app instance 진단 header는 `docker-compose.e2e.yml` overlay에서만 활성화됩니다.
공개 데모와 섞지 않고 아래 명령으로 별도 실행합니다.

```bash
docker compose -f docker-compose.demo.yml -f docker-compose.e2e.yml up --build --wait
(cd web && npm run e2e)
docker compose -f docker-compose.demo.yml -f docker-compose.e2e.yml down --volumes
```

### 5. k6 스크립트 문법 검증

```bash
k6 inspect k6/mixed-chat-test.js
```

### 6. 부하 테스트 실행 예시

REST 조회 API 테스트:

```bash
k6 run --env BASE_URL=http://localhost:8081 k6/rest-api-test.js
```

WebSocket smoke 테스트:

```bash
k6 run \
  --env BASE_URL=http://localhost:8081 \
  --env WS_URL=ws://localhost:8081/ws \
  k6/websocket-test.js
```

Mixed chat scenario:

```bash
k6 run \
  --env BASE_URL=http://localhost:8081 \
  --env WS_URL=ws://localhost:8081/ws \
  k6/mixed-chat-test.js
```

Receiver matrix smoke runner:

이 runner는 Node.js 22의 built-in `fetch`와 `WebSocket`을 사용합니다.

```bash
node scripts/ws-delivery-runner.mjs \
  --base http://localhost:8081 \
  --ws ws://localhost:8081/ws,ws://localhost:8082/ws \
  --users 10 \
  --senders 2 \
  --messages 5 \
  --drain-ms 5000 \
  --out-dir artifacts/ws/smoke
```

같은 artifact에 room list, message history, read receipt HTTP probe를 보조 로그로 남기려면 아래 옵션을
추가합니다. `http.jsonl`은 REST 경로 관측용이며 receiver delivery completeness 분모에는 섞지 않습니다.

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

Multi-room 후보 실행은 room partition을 명시합니다. 이 명령은 도구 준비용이며, artifact 검토 전에는
공개 benchmark로 올리지 않습니다.

```bash
node scripts/ws-delivery-runner.mjs \
  --base http://localhost:8081 \
  --ws ws://localhost:8081/ws,ws://localhost:8082/ws \
  --rooms 2 \
  --users-per-room 3 \
  --senders-per-room 1 \
  --messages 2 \
  --drain-ms 5000 \
  --out-dir artifacts/ws/multi-room-smoke
```

기존 user token과 room을 사용하려면:

```bash
k6 run \
  --env BASE_URL=http://localhost:8081 \
  --env WS_URL=ws://localhost:8081/ws \
  --env AUTH_TOKEN={jwt} \
  --env ROOM_ID=1 \
  k6/mixed-chat-test.js
```

---

## 기술 스택

| 영역 | 기술 |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot 3.4.3 |
| Web | Spring Web, Spring Security |
| Realtime | Spring WebSocket, STOMP |
| Messaging | Apache Kafka 3.9.0 |
| Cache / PubSub / Presence / Rate limit | Redis 7 |
| Database | PostgreSQL 16 |
| Persistence | Spring Data JPA, Hibernate, Flyway |
| Observability | Spring Actuator, Micrometer, Prometheus, Grafana |
| Test | JUnit 5, Mockito, Testcontainers, Awaitility |
| Performance | k6 |
| Infra | Docker Compose |
| Build | Gradle Kotlin DSL |

---

## 테스트 범위

| 테스트 | 검증 내용 |
|---|---|
| `WebSocketAuthorizationInterceptorTest` | SUBSCRIBE membership과 client SEND allowlist 검증 |
| `WebSocketSubscribeAuthorizationIntegrationTest` | 실제 STOMP client 기반 비멤버 구독 및 direct broker SEND exploit 거부 |
| `WebSocketAckIntegrationTest` | WebSocket ACK 수신 흐름 |
| `WebSocketPersistedAckIntegrationTest` | DB 저장 완료 PERSISTED ACK 수신 흐름 |
| `MessageOrderingIntegrationTest` | 같은 room 내 Kafka partition / offset 순서 |
| `DltReplayIntegrationTest` | DLT 격리, manual replay, 중복 replay 멱등성 |
| `ReadReceiptServiceTest` | read receipt 정합성 |
| `PresenceServiceTest` | multi-session presence |
| `MessagePersistenceServiceTest` | Kafka redelivery와 client retry를 구분한 저장/receiver 멱등성 |
| `MessagePersistenceCacheFailureIntegrationTest` | afterCommit cache 장애와 DB message/unread commit 격리 |
| `ConsumerRecoveryIntegrationTest` | Kafka consumer 중복 처리 / 복구 흐름 |
| Playwright two-node E2E | raw Alice와 Bob의 실제 WebSocket upgrade 응답에서 app이 넣은 `x-app-instance`를 확인해 서로 다른 노드임을 증명한 뒤 cross-node delivery/recovery를 검증 |

---

## 한계

| 항목 | 현재 한계 |
|---|---|
| ACK/NACK | ACCEPTED는 Kafka publish 단계의 결과만 의미합니다. PERSISTED는 DB 저장 완료 또는 기존 idempotent row 확인만 의미합니다. WebSocket broadcast 완료, 상대방 수신 완료, 읽음 완료를 보장하지 않습니다. |
| `clientMessageId` | ACK/NACK correlation과 클라이언트 재시도 멱등성 용도입니다. `(senderId, clientMessageId)` unique constraint가 같은 발신자의 같은 클라이언트 메시지 중복 저장을 막습니다. |
| Rate limit | Redis fixed-window 기반 user-level SEND 제한입니다. 초 경계 burst 한계는 [`docs/REDIS_LIMITATIONS.md`](docs/REDIS_LIMITATIONS.md)에 정리했습니다. |
| Kafka ordering | 같은 `roomId`가 같은 partition에 들어가는 범위에 한정됩니다. 서로 다른 room 간 전역 순서는 보장하지 않습니다. |
| DLT replay | 내부 manual utility입니다. 운영 환경에서는 접근 제어, 감사 로그, replay 대상 필터링, 결과 추적이 필요합니다. |
| Redis Pub/Sub fan-out | Pub/Sub는 best-effort입니다. 재연결한 클라이언트는 `lastReceivedMessageId`로 sync API를 호출해 누락 가능성을 보정해야 합니다. |
| Redis Pub/Sub broadcast 실패 | 실패 재전파와 no-ack 동작은 검증했습니다. broadcast 실패의 DLT 적재 end-to-end 검증은 별도 개선 범위입니다. |
| Presence | heartbeat는 클라이언트가 TTL보다 짧은 주기로 `/app/presence.heartbeat`를 보내야 유지됩니다. TTL 만료 이벤트 기반 운영형 감시는 별도 과제입니다. |
| 성능 evidence | 현재 room-list와 persistence pipeline 기준 commit-pinned 재측정 전에는 처리량·지연 수치를 주장하지 않습니다. |
| 과거 측정 문서 | 실행 방법을 보존하는 historical unpinned archive이며 현재 코드 evidence가 아닙니다. |
| Cache | 메시지 저장은 selective eviction이지만, 방 생성/참여는 아직 일부 넓은 무효화 정책이 남아 있습니다. mixed traffic cache hit rate는 추가 측정 예정입니다. |

---

## 문서

| 문서 | 내용 |
|---|---|
| [`docs/DESIGN.md`](docs/DESIGN.md) | 아키텍처, Kafka, WebSocket, DLT, read receipt, presence, cache 설계 |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | README 전체 아키텍처 다이어그램과 설계 경계 |
| [`docs/PERF_RESULT.md`](docs/PERF_RESULT.md) | 과거 REST/k6 실험 기록. historical unpinned archive이며 현재 코드 evidence가 아님 |
| [`docs/TESTING.md`](docs/TESTING.md) | 테스트와 smoke runner가 어떤 claim을 지지하는지 정리 |
| [`docs/RUNBOOK.md`](docs/RUNBOOK.md) | DLT, receiver matrix, presence, cache 관련 대응 절차 초안 |
| [`docs/LIMITATIONS.md`](docs/LIMITATIONS.md) | ACK/PERSISTED, delivery, latency, Redis 한계의 canonical index |
| [`docs/INTERVIEW_GUIDE.md`](docs/INTERVIEW_GUIDE.md) | 면접에서 설명할 핵심 질문과 안전한 답변 |
| [`docs/WEBSOCKET_MEASUREMENT.md`](docs/WEBSOCKET_MEASUREMENT.md) | 과거 WebSocket 실험과 재측정 절차. historical unpinned archive이며 현재 코드 evidence가 아님 |
| [`docs/REDIS_LIMITATIONS.md`](docs/REDIS_LIMITATIONS.md) | Redis fixed-window rate limit과 cache hit rate 한계 |
| [`docs/STUDY_GUIDE.md`](docs/STUDY_GUIDE.md) | 코드 흐름 학습 가이드 |
| [`docs/architecture.drawio`](docs/architecture.drawio) | 이전 컨테이너 다이어그램 참고 자산 |
| [`docs/assets/architecture/overall-architecture.drawio`](docs/assets/architecture/overall-architecture.drawio) | README 전체 아키텍처 편집 참고 자산 |

`monitoring/`의 Prometheus/Grafana 파일은 local template입니다. `/actuator/prometheus`에서 metric과
quantile panel을 직접 검증한 운영 dashboard 증거로 사용하지 않습니다.
