# CLAUDE.md

## 프로젝트 개요

Kafka + WebSocket 기반 실시간 채팅 서비스 (백엔드 포트폴리오)

## 기술 스택

- Java 21, Spring Boot 3.4.3
- Spring WebSocket (STOMP)
- Apache Kafka (KRaft 모드)
- Redis (Pub/Sub + Cache)
- PostgreSQL 16
- Docker Compose
- Testcontainers (통합 테스트)

## 설계 문서

- 설계 문서: `docs/DESIGN.md`
- 성능 측정 결과: `docs/PERF_RESULT.md`

## 패키지 구조

```
src/main/java/com/realtime/chat/
├── config/          # WebSocket, Kafka, Redis, Security 설정
├── controller/      # REST API + WebSocket 메시지 핸들러
├── service/         # 비즈니스 로직
├── consumer/        # Kafka Consumer (DB 저장, 브로드캐스트, 읽음 처리)
├── producer/        # Kafka Producer
├── domain/          # Entity (User, ChatRoom, ChatRoomMember, Message)
├── repository/      # JPA Repository (커서 페이지네이션, 멱등성 쿼리)
├── dto/             # 요청/응답 DTO
├── event/           # Kafka 메시지 스키마 (ChatMessageEvent, ReadReceiptEvent)
└── common/          # JWT, 예외 처리, 필터
```

## 빌드 / 실행

```bash
# 인프라 실행
docker compose up -d

# 애플리케이션 빌드 및 실행
./gradlew bootRun

# 테스트 (Testcontainers로 PostgreSQL, Kafka, Redis 자동 구동)
./gradlew test
```

## API 엔드포인트

### 인증
- `POST /api/auth/signup` — 회원가입
- `POST /api/auth/login` — 로그인

### 채팅방
- `POST /api/rooms/direct` — 1:1 채팅방 생성 (중복 방지)
- `POST /api/rooms/group` — 그룹 채팅방 생성
- `POST /api/rooms/{roomId}/join` — 그룹 채팅방 참여
- `GET /api/rooms` — 내 채팅방 목록
- `GET /api/rooms/{roomId}` — 채팅방 상세

### 메시지
- `GET /api/rooms/{roomId}/messages?cursor={id}&size={n}` — 메시지 이력 (커서 페이지네이션)
- `POST /api/rooms/{roomId}/read` — 읽음 처리

### 온라인 상태
- `GET /api/rooms/{roomId}/members/online` — 채팅방 온라인 멤버 조회

### WebSocket
- STOMP 엔드포인트: `/ws`
- 메시지 전송: `/app/chat.send`
- 구독 (메시지): `/topic/room.{roomId}`
- 구독 (접속 상태): `/topic/presence`

### Actuator
- `GET /actuator/health` — 헬스 체크 (permitAll)
- `GET /actuator/info` — 앱 정보
- `GET /actuator/metrics` — 메트릭

## 코딩 컨벤션

- 언어: 한국어 주석, 영어 코드
- 네이밍: 클래스 PascalCase, 메서드/변수 camelCase, 상수 UPPER_SNAKE_CASE
- DTO: 요청 `*Request`, 응답 `*Response`
- Entity: Lombok 사용 최소화 (`@Getter`, `@NoArgsConstructor(access = PROTECTED)` 정도만)
- 테스트: `*Test` (단위), `*IntegrationTest` (통합)
