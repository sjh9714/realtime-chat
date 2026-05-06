# Realtime Chat

Realtime Chat은 Kafka와 WebSocket(STOMP)을 사용해 실시간 채팅을 구현한 Spring Boot 백엔드입니다. 메시지 저장과 브로드캐스트를 Kafka consumer group으로 분리하고, Redis Pub/Sub으로 여러 애플리케이션 인스턴스 간 메시지를 전달합니다.

## 문제 의식

채팅 서비스는 단순 WebSocket 연결만으로는 확장과 복구가 어렵습니다. 이 저장소는 같은 방의 메시지 순서 보장, 서버 간 브로드캐스트, consumer 장애 복구, 읽음 처리, presence, N+1 개선과 캐시 전략을 함께 다룹니다.

## 주요 기능

- 회원가입, 로그인, JWT 인증
- 1:1 채팅방과 그룹 채팅방 생성
- 채팅방 참여, 목록, 상세 조회
- STOMP WebSocket 메시지 전송과 방 구독
- Kafka 기반 메시지 저장 consumer와 broadcast consumer 분리
- Redis Pub/Sub 기반 서버 간 브로드캐스트
- 메시지 이력 커서 페이지네이션
- 읽음 처리와 unread count 관리
- presence 상태 관리
- WebSocket 메시지 rate limiting
- Prometheus, Grafana 모니터링
- k6 REST/WebSocket 성능 테스트

## 기술 스택

| 영역 | 기술 |
| --- | --- |
| Backend | Java 21, Spring Boot 3.4.3, Spring Web, Spring Security |
| Realtime | Spring WebSocket, STOMP |
| Messaging | Apache Kafka 3.9.0 |
| Data | PostgreSQL 16, Redis 7 |
| Observability | Actuator, Micrometer, Prometheus, Grafana |
| Test / Perf | Testcontainers, JUnit 5, k6 |
| Infra | Docker Compose, Gradle Kotlin DSL |

## 아키텍처

```text
Client
  → WebSocket /app/chat.send
  → App
  → Kafka topic
      ├─ persistence consumer group → PostgreSQL 저장
      └─ broadcast consumer group → Redis Pub/Sub
             └─ 각 App 인스턴스가 구독 후 /topic/room.{roomId}로 전송
```

같은 채팅방의 메시지는 Kafka partition key를 `roomId`로 사용해 순서를 유지합니다. 메시지 저장은 `messageKey` UUID와 DB unique key를 통해 멱등성을 확보합니다.

## 구조

```text
src/main/java/com/realtime/chat/
├── common/       # JWT, 예외 처리, 필터
├── config/       # WebSocket, Kafka, Redis, Security, metrics
├── consumer/     # 메시지 저장/브로드캐스트/읽음 처리 consumer
├── controller/   # REST API와 WebSocket handler
├── domain/       # User, ChatRoom, Message 등
├── dto/          # 요청/응답 DTO
├── event/        # Kafka event payload
├── producer/     # Kafka producer
├── repository/   # JPA repository
└── service/      # 인증, 채팅방, 메시지, 읽음, presence
```

## 실행 방법

Docker Compose로 PostgreSQL, Redis, Kafka, Kafka UI, Prometheus, Grafana, 애플리케이션 2대를 실행합니다.

```bash
docker compose up -d
```

애플리케이션만 로컬에서 실행하려면 인프라를 먼저 띄운 뒤 Gradle을 사용합니다.

```bash
docker compose up -d postgres redis kafka kafka-ui
./gradlew bootRun
```

테스트는 Testcontainers로 PostgreSQL, Kafka, Redis를 자동 구동합니다.

```bash
./gradlew test
```

## 기본 포트

| 서비스 | 포트 |
| --- | --- |
| App 1 | `http://localhost:8081` |
| App 2 | `http://localhost:8082` |
| PostgreSQL | `localhost:5432` |
| Redis | `localhost:6379` |
| Kafka | `localhost:29092` |
| Kafka UI | `http://localhost:8090` |
| Prometheus | `http://localhost:9090` |
| Grafana | `http://localhost:3000` |

## API 요약

| Method | Path | 설명 |
| --- | --- | --- |
| POST | `/api/auth/signup` | 회원가입 |
| POST | `/api/auth/login` | 로그인 |
| POST | `/api/rooms/direct` | 1:1 채팅방 생성 |
| POST | `/api/rooms/group` | 그룹 채팅방 생성 |
| POST | `/api/rooms/{roomId}/join` | 그룹 채팅방 참여 |
| GET | `/api/rooms` | 내 채팅방 목록 |
| GET | `/api/rooms/{roomId}` | 채팅방 상세 |
| GET | `/api/rooms/{roomId}/messages` | 메시지 이력 |
| POST | `/api/rooms/{roomId}/read` | 읽음 처리 |

WebSocket 엔드포인트는 `/ws`, 메시지 전송 경로는 `/app/chat.send`, 구독 경로는 `/topic/room.{roomId}`입니다.

## 문서

- `docs/DESIGN.md`: 아키텍처, ERD, Kafka 토픽, 기술 결정
- `docs/PERF_RESULT.md`: N+1 개선, 인덱스, 캐시, k6 결과
- `docs/STUDY_GUIDE.md`: 코드 흐름 학습 가이드
