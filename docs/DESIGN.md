# 실시간 채팅 서비스 설계 문서

## 컨셉

Kafka와 WebSocket의 기술적 깊이를 증명하는 채팅 서비스.
기능을 많이 만드는 게 아니라, 핵심 문제를 깊게 파고 **정량적으로 검증**한다.

각 챌린지는 **문제 인식 → 대안 비교 → 해결 → 실측 데이터** 순서로 진행하며,
결과는 PERF_RESULT.md에 실측 수치와 Grafana 캡처로 기록한다.

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

## 기술 선택 근거 (ADR)

### Kafka vs RabbitMQ vs Redis Streams

| | Kafka | RabbitMQ | Redis Streams |
|---|---|---|---|
| 순서 보장 | 파티션 단위 보장 | 큐 단위 보장 | 스트림 단위 보장 |
| 처리량 | 매우 높음 | 높음 | 높음 |
| Consumer Group | 네이티브 지원 | 플러그인 필요 | 네이티브 지원 |
| 메시지 재처리 | offset 기반 replay 가능 | 어려움 (ACK 후 삭제) | 가능 |
| 운영 복잡도 | 높음 | 낮음 | 낮음 |

**선택: Kafka** — 파티션 기반 순서 보장 + Consumer Group으로 독립적 처리(DB 저장, 브로드캐스트, 알림)가 핵심 요구사항. offset 기반 재처리로 장애 복구 실험이 가능한 점이 포트폴리오 기술 챌린지와 부합.

### STOMP vs Raw WebSocket vs SSE

| | STOMP | Raw WebSocket | SSE |
|---|---|---|---|
| 구독/발행 패턴 | 프로토콜 레벨 지원 | 직접 구현 필요 | 단방향만 가능 |
| 메시지 라우팅 | destination 기반 | 직접 구현 필요 | 불가 |
| Spring 지원 | MessageMapping 등 내장 | 기본 지원 | 기본 지원 |
| 양방향 통신 | 가능 | 가능 | 불가 |

**선택: STOMP** — 채팅방별 구독(`/topic/room.{roomId}`)이 프로토콜 레벨에서 지원되어 구현 복잡도가 낮음. Spring의 MessageMapping과 자연스럽게 통합.

### JSON vs Avro vs Protobuf (메시지 직렬화)

**선택: JSON (Jackson)** — 디버깅/모니터링 시 가독성, Kafka UI에서 메시지 확인 용이. 포트폴리오 수준에서 충분한 성능. 추후 성능 병목 발생 시 Avro/Protobuf 전환 고려 가능.

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

### 메시지 경로 트레이드오프

모든 메시지가 Kafka를 거친 후 클라이언트에게 도달하는 구조를 선택했다.

| | 현재 설계 (Kafka 경유) | 대안 (Redis 즉시 브로드캐스트) |
|---|---|---|
| 흐름 | Client → Kafka → Consumer → Redis Pub/Sub → Client | Client → Redis Pub/Sub → Client (즉시) + Kafka (비동기 저장) |
| 장점 | Kafka 파티션 기반 순서 보장이 브로드캐스트에도 적용 | 사용자 체감 레이턴시가 낮음 |
| 단점 | Kafka consume 지연이 체감 레이턴시에 포함 | 브로드캐스트 순서와 DB 저장 순서가 불일치할 수 있음 |

**선택 이유**: 채팅 서비스에서 메시지 순서 보장이 레이턴시 수 ms 차이보다 중요하다고 판단. Kafka를 Single Source of Truth로 두어 모든 Consumer가 동일한 순서를 보장받는 것이 아키텍처 일관성에 유리하다.

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
├── message_key (UK, UUID, Producer가 발행 시 생성하는 멱등성 키)
├── room_id (FK → chat_rooms)
├── sender_id (FK → users)
├── content (text)
├── type (enum: TEXT/IMAGE/SYSTEM)
├── kafka_partition (int)
├── kafka_offset (bigint)
├── created_at (datetime)
├── INDEX: (room_id, created_at)
└── UK: (kafka_partition, kafka_offset)
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

### Hot Partition 리스크

- partition key = roomId는 순서 보장에 최적이지만, 특정 방에 트래픽이 몰리면 하나의 파티션에 부하 집중
- 현재 설계에서는 roomId 파티셔닝이 순서 보장과 단순성에서 최선이라 판단
- 대규모 서비스 확장 시에는 인기 채팅방 분산을 위한 추가 전략 필요 (sub-partitioning 등)
- Challenge 1의 비교 실험에서 hot partition 시나리오도 함께 측정

---

## 핵심 기술 챌린지

면접 스토리의 메인. 각 챌린지를 **문제 인식 → 대안 비교 → 해결 → 실측 데이터**로 깊게 판다.

### 1. 메시지 순서 보장

- **문제**: 같은 방 메시지가 뒤바뀌면 대화가 깨짐
- **해결**: partition key = roomId로 같은 방 메시지를 같은 파티션에 보장
- **검증**: k6로 동시 100명이 같은 방에 메시지 전송 → 순서 정확도 측정
- **비교 실험**: partition key를 userId로 바꿨을 때 순서 역전 재현 + hot partition 시나리오 측정
- **측정 계획**: 순서 정확도(%), 메시지 전달 레이턴시(p50/p95/p99) → PERF_RESULT.md에 실측 기록

### 2. WebSocket 스케일아웃

- **문제**: 서버 1대일 때는 되는데, 2대 이상이면 다른 서버의 유저에게 메시지가 안 감
- **해결**: Redis Pub/Sub로 서버 간 메시지 브로드캐스트
- **검증**: Docker Compose로 서버 2대 구동, 각 서버에 연결된 유저 간 채팅 정상 동작 확인
- **측정 계획**: 서버 1대 vs 2대 레이턴시 비교, 동시 접속 수 증가에 따른 성능 변화 → PERF_RESULT.md에 실측 기록

### 3. 읽음 처리 동시성

- **문제**: 그룹채팅에서 여러 명이 동시에 읽으면 unread_count 업데이트가 꼬임
- **대안 비교**:
  - DB 낙관적 락 (`@Version`) — 충돌 시 재시도, 충돌률 높으면 성능 저하
  - DB 비관적 락 (`SELECT ... FOR UPDATE`) — 대기 시간 발생, 데드락 리스크
  - Redis 원자적 연산 (`DECR`) — 네트워크 1회, 락 불필요
- **해결**: Redis DECR 원자적 연산으로 카운트 관리, 주기적 배치로 DB 벌크 동기화
- **Redis 장애 복구**: DB의 `last_read_message_id` 기준으로 unread_count 재계산 fallback (`SELECT COUNT(*) FROM messages WHERE room_id = ? AND id > last_read_message_id`). Redis는 순수 캐시 역할이므로 장애 시에도 데이터 정합성 보장
- **측정 계획**: 3가지 전략의 50명 동시 읽음 처리 시간 + 정합성 비교, 건별 vs 벌크 DB 동기화 성능 비교 → PERF_RESULT.md에 실측 기록

### 4. Consumer 장애 복구

- **문제**: Consumer가 죽었다 살아나면 메시지 유실 or 중복, 클라이언트는 끊긴 동안 메시지 못 받음
- **해결 (서버)**: manual offset commit + 멱등성 처리 (message_key UUID 기반 중복 체크)
- **멱등성 보장**: Producer가 메시지 발행 시 UUID(message_key)를 생성하고, Consumer가 DB 저장 시 UK 제약으로 중복 삽입 방지
- **해결 (클라이언트)**: 재연결 시 last_read_message_id 이후 메시지를 커서 기반 페이지네이션으로 조회 (MVP의 메시지 이력 조회 API와 동일 인터페이스)
- **DLT**: 3회 재시도 실패 시 Dead Letter Topic 격리, 모니터링 알림
- **검증 시나리오**: Consumer 강제 종료 → 재기동 후 유실 0건/중복 0건 확인, 의도적 연결 끊김 → 재연결 후 누락 메시지 0건

---

## 성능 최적화

부하 테스트로 병목을 발견하고, **실측 데이터로 개선을 증명**한다.

### 5. DB 인덱스 최적화

- **문제**: 메시지가 대량 쌓이면 채팅방별 메시지 조회(커서 페이지네이션) 속도 저하
- **분석**: `EXPLAIN ANALYZE`로 쿼리 플랜 확인, Sequential Scan vs Index Scan 비교
- **해결**: `messages (room_id, created_at)` 복합 인덱스 적용
- **추가 실험**: 커버링 인덱스 적용 시 효과 측정
- **측정 계획**: 100만 건 기준 인덱스 적용 전/후 조회 레이턴시, 쿼리 플랜 캡처 → PERF_RESULT.md에 실측 기록

### 6. 채팅방 목록 쿼리 최적화

- **문제**: 채팅방 목록 조회 시 각 방의 마지막 메시지 + 안읽은 수를 개별 쿼리로 조회 → N+1 발생
- **해결**: fetch join + 서브쿼리로 쿼리 횟수 축소
- **Redis 캐싱**: 채팅방 메타 정보, 사용자 프로필을 Cache Aside 패턴으로 캐싱. 데이터 변경 시 캐시 키 삭제 (write-invalidate). Cache Stampede 방지를 위한 분산 락 적용
- **측정 계획**: N+1 해결 전/후 쿼리 수 및 응답 시간 비교, Redis Cache Hit Rate 및 캐시 적용 전/후 응답 시간 비교 → PERF_RESULT.md에 실측 기록

---

## 프로덕션 품질

"이런 것까지 생각했다"는 운영 감각을 보여주는 요소.

### 7. 모니터링 체계

- **수집 메트릭**:
  - 메시지 전달 레이턴시 (p50 / p95 / p99) — Kafka 발행부터 WebSocket 수신까지
  - Kafka Consumer Lag — 처리 지연 감지
  - WebSocket 동시 접속 수 — 서버별 세션 수
  - 메시지 처리 TPS — 초당 처리량
  - JVM 메트릭 — GC, 스레드, 힙 사용량
- **구성**: Spring Actuator + Micrometer → Prometheus → Grafana 대시보드
- **활용**: k6 부하 테스트 중 Grafana 대시보드를 캡처하여 병목 지점을 시각적으로 문서화

### 8. 운영 안정성

- **Graceful Shutdown**: Kafka Consumer가 메시지 처리 중일 때 서버 종료 시 현재 배치 처리 완료 후 offset commit 후 종료. 처리 중 메시지 유실 방지
- **Rate Limiting**: WebSocket 메시지 폭탄 방지. 클라이언트별 초당 메시지 전송 제한, 초과 시 경고 후 연결 종료
- **Health Check**: `/actuator/health` 엔드포인트로 Kafka, Redis, PostgreSQL 연결 상태 확인. Docker Compose의 healthcheck와 연동

---

## 기능 목록

### MVP (1차)

- [ ] 회원가입 / 로그인 (JWT)
- [ ] 1:1 채팅방 생성
- [ ] 그룹 채팅방 생성 / 참여
- [ ] 실시간 메시지 전송 (WebSocket + Kafka)
- [ ] 메시지 이력 조회 (커서 기반 페이지네이션)
- [ ] 읽음 처리 + 안읽은 메시지 수

### 2차 (기술 챌린지)

- [ ] WebSocket 스케일아웃 (Redis Pub/Sub, 서버 2대)
- [ ] Consumer 장애 복구 + DLT + 멱등성
- [ ] 연결 끊김 복구 (재연결 시 미수신 메시지 보정)
- [ ] 온라인/오프라인 상태 표시
- [ ] Graceful Shutdown / Rate Limiting / Health Check

### 3차 (성능 최적화 + 수치화)

- [ ] Prometheus + Grafana 대시보드 구축
- [ ] k6 WebSocket 부하 테스트 시나리오 작성
- [ ] 파티셔닝 전략 비교 실험 (roomId vs userId)
- [ ] 스케일아웃 레이턴시 비교 (1대 vs 2대)
- [ ] DB 인덱스 최적화 — EXPLAIN ANALYZE 전/후 실측
- [ ] 채팅방 목록 N+1 해결 + Redis 캐싱 적용
- [ ] 읽음 처리 동시성 전략 비교 (낙관적 락 vs 비관적 락 vs Redis)
- [ ] 성능 테스트 결과 문서화 (PERF_RESULT.md)

---

## 문서화 전략

포트폴리오는 코드만으로는 부족하다. **보여주는 방식**이 절반이다.

| 산출물 | 내용 | 위치 |
|--------|------|------|
| README.md | 아키텍처 다이어그램 + 핵심 챌린지 요약 + 실행 방법 | 프로젝트 루트 |
| DESIGN.md | 설계 문서 (본 문서) | docs/ |
| PERF_RESULT.md | 실측 데이터 + Grafana 캡처 + 전/후 비교표 | docs/ |
| Docker Compose | `docker compose up` 한 줄로 전체 환경 실행 | 프로젝트 루트 |

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

## WebSocket 인증

- WebSocket handshake 시 HTTP 헤더에서 JWT 토큰 검증 (`HandshakeInterceptor`)
- STOMP CONNECT 프레임의 `Authorization` 헤더로 토큰 전달
- 토큰 만료 시 서버에서 WebSocket 세션 종료 → 클라이언트가 토큰 갱신 후 재연결
- WebSocket 세션에 userId를 바인딩하여 이후 메시지 처리에 활용

---

## 테스트 전략

| 구분 | 범위 | 도구 |
|------|------|------|
| 단위 테스트 | Service, Consumer 비즈니스 로직 | JUnit 5, Mockito |
| 통합 테스트 | Kafka Producer/Consumer, WebSocket 연결/재연결 | Testcontainers, Spring Boot Test |
| 멱등성 테스트 | 동일 message_key 중복 발행 시 DB 저장 1건 확인 | Testcontainers (Kafka + PostgreSQL) |
| 부하 테스트 | WebSocket 동시 접속, 메시지 처리량, 레이턴시 | k6 (WebSocket) |

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
├── event/           # Kafka 메시지 스키마 (발행/소비용 이벤트 객체)
└── common/          # 공통 유틸, 예외 처리
```
