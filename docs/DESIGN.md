# 실시간 채팅 서비스 설계 문서

## 컨셉

Kafka와 WebSocket의 기술적 깊이를 증명하는 채팅 서비스.
기능을 많이 만드는 게 아니라, 핵심 문제(순서 보장, 스케일아웃, 장애 복구)를 깊게 파고 정량적으로 검증한다.

---

## 기술 스택

| 영역 | 기술 | 선택 이유 |
|------|------|----------|
| Runtime | Java 21, Spring Boot 3 | Virtual Thread 활용 가능, 검증된 에코시스템 |
| 실시간 | Spring WebSocket (STOMP) | STOMP 프로토콜로 구독/발행 패턴 지원 |
| 메시지 파이프라인 | Apache Kafka | 높은 처리량, 파티션 기반 순서 보장, Consumer Group |
| 세션 공유 | Redis Pub/Sub | 서버 다중 인스턴스 간 WebSocket 메시지 브로드캐스트 |
| 캐시 | Redis | 읽음 수, 온라인 상태 등 빈번한 업데이트에 적합 |
| 저장소 | PostgreSQL | 메시지 영구 저장, 안정성 |
| 인프라 | Docker Compose | 로컬 개발 환경 일괄 구성 |
| 부하 테스트 | k6 (WebSocket) | WebSocket 프로토콜 부하 테스트 지원 |
| 모니터링 | Prometheus, Grafana, Kafka UI | Kafka Consumer 랙, RPS, 레이턴시 모니터링 |

---

## 아키텍처

```
Client (WebSocket/STOMP)
    │
    ▼
┌─────────────────────────────┐
│  Spring Boot (Instance 1~N) │
│  ┌─────────┐  ┌───────────┐ │
│  │WebSocket │  │ REST API  │ │
│  │Handler   │  │(방 생성 등)│ │
│  └────┬─────┘  └───────────┘ │
│       │ publish               │
└───────┼───────────────────────┘
        ▼
┌──────────────┐    ┌──────────────┐
│    Kafka     │    │  Redis       │
│ ┌──────────┐ │    │ - Pub/Sub    │
│ │messages  │ │    │   (세션공유) │
│ │(by room) │ │    │ - Cache      │
│ ├──────────┤ │    │   (읽음수 등)│
│ │read-     │ │    └──────────────┘
│ │receipts  │ │
│ ├──────────┤ │
│ │messages  │ │
│ │.dlt      │ │
│ └──────────┘ │
└──────┬───────┘
       │ consume
       ▼
┌──────────────────────────────┐
│ Consumer Group 1: DB 저장     │
│ Consumer Group 2: WebSocket   │
│   브로드캐스트 (Redis Pub/Sub)│
│ Consumer Group 3: 알림 발송   │
└──────────┬───────────────────┘
           ▼
      ┌──────────┐
      │PostgreSQL│
      └──────────┘
```

### 메시지 흐름

1. 클라이언트가 STOMP로 메시지 전송
2. WebSocket Handler → Kafka `chat.messages` 토픽에 발행 (partition key = roomId)
3. **Consumer Group 1**: 메시지를 PostgreSQL에 저장
4. **Consumer Group 2**: Redis Pub/Sub로 모든 서버 인스턴스에 브로드캐스트 → 각 인스턴스가 해당 방에 연결된 클라이언트에게 WebSocket 전송
5. **Consumer Group 3**: 오프라인 유저에게 알림 처리

---

## ERD

```
users
├── id (PK, bigint)
├── email (UK, varchar)
├── password (varchar)
├── nickname (varchar)
├── status (enum: ONLINE/OFFLINE)
├── last_seen_at (datetime)
└── created_at (datetime)

chat_rooms
├── id (PK, bigint)
├── name (varchar)
├── type (enum: DIRECT/GROUP)
├── created_by (FK → users)
└── created_at (datetime)

chat_room_members
├── id (PK, bigint)
├── room_id (FK → chat_rooms)
├── user_id (FK → users)
├── last_read_message_id (FK → messages, nullable)
├── unread_count (int)
├── joined_at (datetime)
└── UK: (room_id, user_id)

messages
├── id (PK, bigint)
├── room_id (FK → chat_rooms)
├── sender_id (FK → users)
├── content (text)
├── type (enum: TEXT/IMAGE/SYSTEM)
├── kafka_offset (bigint, 메시지 추적용)
├── created_at (datetime)
└── INDEX: (room_id, created_at)
```

---

## Kafka 토픽 설계

| 토픽 | Partition Key | 파티션 수 | 목적 |
|------|-------------|----------|------|
| `chat.messages` | roomId | 6 | 채팅 메시지 (순서 보장) |
| `chat.read-receipts` | roomId | 3 | 읽음 처리 |
| `chat.messages.dlt` | — | 1 | 실패 메시지 격리 |

### 파티셔닝 전략

- **partition key = roomId**: 같은 방의 메시지가 같은 파티션에 들어가므로 순서 보장
- 비교 실험: userId로 파티셔닝하면 같은 방 메시지가 다른 파티션에 분산되어 순서가 깨지는 케이스 발생
- 파티션 수 6개: Consumer 인스턴스 확장(최대 6대)에 대비

### Consumer 설정

- `enable.auto.commit = false` (manual offset commit)
- `isolation.level = read_committed`
- 처리 실패 시 3회 재시도 → 실패 시 DLT로 격리
- Consumer Group별 독립 처리 (DB 저장, 브로드캐스트, 알림)

---

## 핵심 기술 챌린지

각 챌린지가 포트폴리오의 **문제 → 해결 → 정량적 결과** 스토리가 된다.

### 1. 메시지 순서 보장

- **문제**: 같은 방 메시지가 뒤바뀌면 대화가 깨짐
- **해결**: partition key = roomId로 같은 방 메시지를 같은 파티션에 보장
- **검증**: k6로 동시 100명이 같은 방에 메시지 전송 → 순서 정확도 100% 확인
- **비교**: partition key를 userId로 바꿨을 때 순서 깨지는 케이스 문서화

### 2. WebSocket 스케일아웃

- **문제**: 서버 1대일 때는 되는데, 2대 이상이면 다른 서버의 유저에게 메시지가 안 감
- **해결**: Redis Pub/Sub로 서버 간 메시지 브로드캐스트
- **검증**: Docker Compose로 서버 2대 구동, 각 서버에 연결된 유저 간 채팅 정상 동작 확인
- **측정**: 서버 1대 vs 2대 레이턴시 비교

### 3. 읽음 처리 동시성

- **문제**: 그룹채팅에서 여러 명이 동시에 읽으면 unread_count 업데이트가 꼬임
- **해결**: Redis DECR 원자적 연산으로 카운트 관리, 주기적으로 DB 동기화
- **검증**: 50명 동시 읽음 처리 테스트 → 정합성 100%

### 4. Consumer 장애 복구

- **문제**: Consumer가 죽었다 살아나면 메시지 유실 or 중복
- **해결**: manual offset commit + 멱등성 처리 (message ID 기반 중복 체크)
- **검증**: Consumer 강제 종료 → 재기동 후 유실 0건 확인
- **DLT**: 3회 재시도 실패 시 Dead Letter Topic 격리, 모니터링 알림

### 5. 연결 끊김 복구

- **문제**: 네트워크 끊김 동안 수신 못한 메시지
- **해결**: 재연결 시 last_read_message_id 이후 메시지를 REST API로 일괄 전송
- **검증**: 의도적 연결 끊김 → 재연결 후 누락 메시지 0건

---

## 기능 목록

### MVP (1차)

- [ ] 회원가입 / 로그인 (JWT)
- [ ] 1:1 채팅방 생성
- [ ] 그룹 채팅방 생성 / 참여
- [ ] 실시간 메시지 전송 (WebSocket + Kafka)
- [ ] 메시지 이력 조회 (커서 기반 페이지네이션)
- [ ] 읽음 처리 + 안읽은 메시지 수

### 2차

- [ ] WebSocket 스케일아웃 (Redis Pub/Sub, 서버 2대)
- [ ] Consumer 장애 복구 + DLT
- [ ] 연결 끊김 복구
- [ ] 온라인/오프라인 상태 표시

### 3차 (성능 검증)

- [ ] k6 WebSocket 부하 테스트 시나리오 작성
- [ ] 파티셔닝 전략 비교 실험 (roomId vs userId)
- [ ] 스케일아웃 레이턴시 비교 (1대 vs 2대)
- [ ] Prometheus + Grafana 대시보드 (Kafka Consumer 랙, RPS, 레이턴시)
- [ ] 성능 테스트 결과 문서화 (PERF_RESULT.md)

---

## Docker Compose 구성 (예정)

```yaml
services:
  app-1:        # Spring Boot 인스턴스 1
  app-2:        # Spring Boot 인스턴스 2 (스케일아웃 검증)
  postgres:     # PostgreSQL
  redis:        # Redis (Pub/Sub + Cache)
  kafka:        # Apache Kafka (KRaft 모드)
  kafka-ui:     # Kafka UI (토픽/컨슈머 모니터링)
  prometheus:   # 메트릭 수집
  grafana:      # 대시보드
```

---

## 디렉토리 구조 (예정)

```
src/main/java/com/realtime/chat/
├── config/          # WebSocket, Kafka, Redis, Security 설정
├── controller/      # REST API + WebSocket 메시지 핸들러
├── service/         # 비즈니스 로직
├── consumer/        # Kafka Consumer (DB 저장, 브로드캐스트, 알림)
├── producer/        # Kafka Producer
├── domain/          # Entity
├── repository/      # JPA Repository
├── dto/             # 요청/응답 DTO
└── common/          # 공통 유틸, 예외 처리
```
