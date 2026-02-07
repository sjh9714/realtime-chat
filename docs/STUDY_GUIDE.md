# 실시간 채팅 서비스 학습 가이드

이 문서는 프로젝트의 모든 코드를 **처음 배우는 사람도 이해할 수 있도록** 설명합니다.
"이 코드가 왜 여기에 있는지", "이 기술이 뭔지", "전체가 어떻게 연결되는지"를 다룹니다.

---

## 목차

### 1차: MVP
1. [프로젝트 전체 그림](#1-프로젝트-전체-그림)
2. [기술 스택 개념 정리](#2-기술-스택-개념-정리)
3. [프로젝트 구조 이해하기](#3-프로젝트-구조-이해하기)
4. [STEP 1: 프로젝트 뼈대](#4-step-1-프로젝트-뼈대)
5. [STEP 2: 데이터베이스 설계 (Entity + Repository)](#5-step-2-데이터베이스-설계)
6. [STEP 3: JWT 인증](#6-step-3-jwt-인증)
7. [STEP 4: 채팅방 CRUD](#7-step-4-채팅방-crud)
8. [STEP 5: Kafka 메시지 파이프라인](#8-step-5-kafka-메시지-파이프라인)
9. [STEP 6: WebSocket + Redis Pub/Sub](#9-step-6-websocket--redis-pubsub)
10. [STEP 7: 메시지 이력 + 읽음 처리](#10-step-7-메시지-이력--읽음-처리)
11. [STEP 8: 테스트](#11-step-8-테스트)
12. [메시지 전체 흐름 따라가기](#12-메시지-전체-흐름-따라가기)
13. [자주 묻는 질문](#13-자주-묻는-질문)

### 2차: 프로덕션 품질
14. [STEP 9: Health Check + Graceful Shutdown](#14-step-9-health-check--graceful-shutdown)
15. [STEP 10: Rate Limiting](#15-step-10-rate-limiting)
16. [STEP 11: Consumer DLT 심화](#16-step-11-consumer-dlt-심화)
17. [STEP 12: 온라인/오프라인 상태](#17-step-12-온라인오프라인-상태)
18. [STEP 13: 스케일아웃 + 통합 테스트](#18-step-13-스케일아웃--통합-테스트)

---

## 1. 프로젝트 전체 그림

### 한 줄 요약

> 사용자가 채팅 메시지를 보내면, **Kafka**가 순서를 보장하며 전달하고, **Redis**가 모든 서버에 뿌리고, **WebSocket**으로 실시간 수신한다.

### 전체 흐름 (쉬운 버전)

```
[사용자 A] --- WebSocket으로 메시지 전송 --→ [Spring Boot 서버]
                                                  │
                                           Kafka에 메시지 저장
                                                  │
                                         ┌────────┴────────┐
                                         ▼                  ▼
                                   [Consumer 1]       [Consumer 2]
                                   DB에 저장           Redis로 발행
                                                        │
                                                  ┌─────┴─────┐
                                                  ▼           ▼
                                             [서버 1]     [서버 2]
                                                  │           │
                                             WebSocket    WebSocket
                                                  │           │
                                             [사용자 B]   [사용자 C]
```

### 왜 이렇게 복잡하게?

**"서버가 1대면 간단한데, 2대 이상이면?"** 이 질문이 핵심입니다.

- 서버 1대: 사용자 A가 보낸 메시지를 같은 서버의 사용자 B에게 바로 전달하면 됨
- 서버 2대: 사용자 A는 서버1에, 사용자 B는 서버2에 연결되어 있다면? → **서버 간 통신이 필요**

이 문제를 해결하기 위해:
- **Kafka**: 메시지를 중앙에서 관리하고, 순서를 보장
- **Redis Pub/Sub**: 모든 서버에 메시지를 브로드캐스트
- **WebSocket**: 서버→클라이언트 실시간 전달

---

## 2. 기술 스택 개념 정리

### Spring Boot

웹 서버를 쉽게 만들어주는 프레임워크입니다.

```
일반 Java: HTTP 요청 파싱, 응답 생성, 서버 실행... 전부 직접 해야 함
Spring Boot: @RestController 하나만 붙이면 API 서버 완성
```

이 프로젝트에서는 REST API(채팅방 생성, 로그인 등)와 WebSocket 서버를 동시에 운영합니다.

### JPA (Java Persistence API)

Java 객체를 DB 테이블에 자동으로 매핑해주는 기술입니다.

```java
// 이 Java 클래스가 → DB의 users 테이블에 대응
@Entity
@Table(name = "users")
public class User {
    @Id
    private Long id;      // → id 컬럼
    private String email;  // → email 컬럼
}
```

**SQL을 직접 안 써도** `userRepository.save(user)` 하면 INSERT가 실행됩니다.

### JWT (JSON Web Token)

로그인 상태를 유지하는 방법입니다.

```
[전통적 방식 - 세션]
로그인 → 서버가 세션 ID 발급 → 서버 메모리에 "이 세션은 홍길동" 저장
문제: 서버가 2대면? 서버1에서 로그인했는데 서버2는 모름

[JWT 방식]
로그인 → 서버가 토큰 발급 (내용: "userId=1, 만료=내일") + 비밀키로 서명
클라이언트가 매 요청마다 토큰 전송 → 서버가 서명 확인만 하면 됨
장점: 서버가 아무것도 저장할 필요 없음 (stateless) → 서버 확장 쉬움
```

JWT 토큰 구조:
```
eyJhbGciOiJIUzI1NiJ9.       ← Header (알고리즘)
eyJzdWIiOiIxIiwiZW1haWwiOi.. ← Payload (userId, email, 만료시간)
SflKxwRJSMeKKF2QT4fwpM..    ← Signature (위변조 방지 서명)
```

### Kafka

**분산 메시지 큐**입니다. 쉽게 말하면 **우체국** 같은 것입니다.

```
[일반적인 방식]
A → 직접 B에게 전달
문제: B가 잠깐 꺼져있으면? 메시지 유실!

[Kafka 방식]
A → Kafka(우체국)에 맡김 → Kafka가 B에게 배달
장점: B가 꺼져있어도 Kafka가 보관하고 있다가, B가 켜지면 배달
```

핵심 용어:
```
Producer   : 메시지를 보내는 쪽 (우편 발송인)
Consumer   : 메시지를 받는 쪽 (수신인)
Topic      : 메시지 분류함 (chat.messages, chat.read-receipts)
Partition  : 토픽을 나눈 칸 (순서 보장의 핵심!)
Offset     : 각 메시지의 순번 (몇 번째 메시지까지 읽었는지 추적)
Consumer Group : 같은 메시지를 다른 용도로 처리하는 그룹
```

**파티션이 왜 중요한가?**
```
chat.messages 토픽 (6개 파티션)
┌──────────┐ ┌──────────┐ ┌──────────┐
│Partition 0│ │Partition 1│ │Partition 2│  ...
│ 방1 메시지 │ │ 방2 메시지 │ │ 방3 메시지 │
│  순서보장  │ │  순서보장  │ │  순서보장  │
└──────────┘ └──────────┘ └──────────┘

partition key = roomId → 같은 방의 메시지는 항상 같은 파티션
→ 파티션 안에서는 순서가 보장됨!
```

**Consumer Group이 왜 필요한가?**
```
같은 메시지를 2가지 용도로 써야 함:
1. DB에 저장 (Consumer Group 1: chat-persistence)
2. 실시간 전달 (Consumer Group 2: chat-broadcast)

Consumer Group이 다르면 같은 메시지를 각각 받을 수 있음!
```

### WebSocket / STOMP

**WebSocket**: 서버와 클라이언트가 연결을 유지하며 양방향 통신하는 프로토콜입니다.

```
[HTTP]
클라이언트: "새 메시지 있어?" → 서버: "없어"
클라이언트: "새 메시지 있어?" → 서버: "없어"
클라이언트: "새 메시지 있어?" → 서버: "있어! 여기"
→ 계속 물어봐야 함 (폴링), 비효율적

[WebSocket]
클라이언트 ←→ 서버 (연결 유지)
서버: "새 메시지 왔어!" (서버가 먼저 보낼 수 있음)
→ 실시간 통신 가능
```

**STOMP**: WebSocket 위에서 동작하는 메시지 프로토콜입니다.

```
STOMP 없이: 메시지가 오면 "이게 채팅인지, 알림인지, 어느 방인지" 직접 구분해야 함
STOMP 사용: 구독/발행 패턴으로 깔끔하게 처리

클라이언트: SUBSCRIBE /topic/room.1     ← "1번 방 메시지 구독"
서버:       SEND /topic/room.1 "안녕"    ← 1번 방 구독자 전원에게 전달
```

### Redis

**인메모리 데이터 저장소**입니다. RAM에 저장하므로 DB보다 훨씬 빠릅니다.

이 프로젝트에서 2가지 용도:

```
1. Pub/Sub (서버 간 메시지 전달)
   서버1: PUBLISH "chat:room:1" "안녕하세요"
   서버2: SUBSCRIBE "chat:room:*" → "안녕하세요" 수신!
   → 서버가 여러 대여도 모든 서버가 메시지를 받을 수 있음

2. Cache (빠른 읽기)
   "1번 방에서 유저A의 안읽은 메시지 수" → DB 매번 조회하면 느림
   → Redis에 캐싱해두고, DB는 가끔만 조회
```

### Docker Compose

여러 프로그램(PostgreSQL, Redis, Kafka)을 **한 번에 실행**하는 도구입니다.

```bash
# 이 한 줄로 PostgreSQL + Redis + Kafka + Kafka UI 전부 실행
docker compose up -d

# 없었다면?
# PostgreSQL 설치하고... 설정하고...
# Redis 설치하고... 설정하고...
# Kafka 설치하고... Zookeeper 설치하고... (KRaft 모드로 Zookeeper 불필요)
```

### Testcontainers

테스트할 때 **Docker 컨테이너를 자동으로 띄워주는** 라이브러리입니다.

```
[문제] 통합 테스트하려면 PostgreSQL, Kafka, Redis가 실행되어 있어야 함
[해결] Testcontainers가 테스트 시작할 때 자동으로 Docker 컨테이너를 띄우고,
       테스트 끝나면 자동으로 정리
→ docker compose up 없이 ./gradlew test만 실행하면 됨!
```

---

## 3. 프로젝트 구조 이해하기

### 계층 구조 (Layered Architecture)

```
클라이언트 요청
    │
    ▼
┌─────────────┐
│ Controller  │  요청을 받고, 응답을 보냄 (교통 경찰)
│             │  "POST /api/auth/signup 이 왔네? AuthService야 처리해줘"
└──────┬──────┘
       ▼
┌─────────────┐
│  Service    │  비즈니스 로직 (실제 일하는 직원)
│             │  "이메일 중복 확인하고, 비밀번호 암호화하고, 유저 저장"
└──────┬──────┘
       ▼
┌─────────────┐
│ Repository  │  DB와 대화 (창구 직원)
│             │  "이 유저를 users 테이블에 INSERT 해줘"
└──────┬──────┘
       ▼
   [Database]
```

### 패키지별 역할

```
src/main/java/com/realtime/chat/
│
├── ChatApplication.java        ← 앱 시작점 (main 메서드)
│
├── config/                     ← 설정 (Spring에게 "이렇게 동작해"라고 알려줌)
│   ├── SecurityConfig.java     ← 보안: 어떤 URL은 로그인 없이 접근 가능하게
│   ├── KafkaConfig.java        ← Kafka: 토픽 생성, Consumer 설정
│   ├── WebSocketConfig.java    ← WebSocket: STOMP 엔드포인트 설정
│   ├── WebSocketAuthInterceptor.java ← WebSocket 연결 시 JWT 검증
│   └── RedisConfig.java        ← Redis: Pub/Sub 채널 구독 설정
│
├── common/                     ← 여러 곳에서 공통으로 쓰는 것들
│   ├── JwtTokenProvider.java   ← JWT 토큰 생성/검증
│   ├── JwtAuthenticationFilter.java ← 매 HTTP 요청마다 JWT 확인
│   ├── GlobalExceptionHandler.java  ← 에러 발생 시 깔끔한 응답
│   ├── BusinessException.java  ← 비즈니스 에러 (이메일 중복 등)
│   └── ErrorResponse.java      ← 에러 응답 형식
│
├── domain/                     ← DB 테이블과 1:1 매핑되는 클래스들
│   ├── User.java               ← users 테이블
│   ├── ChatRoom.java           ← chat_rooms 테이블
│   ├── ChatRoomMember.java     ← chat_room_members 테이블
│   ├── Message.java            ← messages 테이블
│   ├── UserStatus.java         ← ONLINE / OFFLINE
│   ├── RoomType.java           ← DIRECT / GROUP
│   └── MessageType.java        ← TEXT / IMAGE / SYSTEM
│
├── repository/                 ← DB CRUD 인터페이스
│   ├── UserRepository.java
│   ├── ChatRoomRepository.java
│   ├── ChatRoomMemberRepository.java
│   └── MessageRepository.java
│
├── dto/                        ← 요청/응답 데이터 형식
│   ├── SignupRequest.java      ← 회원가입 요청 {"email", "password", "nickname"}
│   ├── LoginRequest.java       ← 로그인 요청 {"email", "password"}
│   ├── AuthResponse.java       ← 인증 응답 {"token", "userId", ...}
│   ├── CreateDirectRoomRequest.java  ← 1:1방 생성 {"targetUserId"}
│   ├── CreateGroupRoomRequest.java   ← 그룹방 생성 {"name", "memberIds"}
│   ├── ChatRoomResponse.java         ← 채팅방 응답
│   ├── ChatRoomListResponse.java     ← 채팅방 목록 응답
│   ├── SendMessageRequest.java       ← 메시지 전송 {"roomId", "content"}
│   ├── MessageResponse.java          ← 메시지 응답
│   ├── MessagePageResponse.java      ← 페이지네이션 응답 {"messages", "hasMore"}
│   └── ReadReceiptRequest.java       ← 읽음 처리 {"lastReadMessageId"}
│
├── event/                      ← Kafka로 주고받는 메시지 형식
│   ├── ChatMessageEvent.java   ← 채팅 메시지 이벤트
│   └── ReadReceiptEvent.java   ← 읽음 처리 이벤트
│
├── producer/                   ← Kafka에 메시지를 보내는 역할
│   └── ChatMessageProducer.java
│
├── consumer/                   ← Kafka에서 메시지를 받아 처리하는 역할
│   ├── MessagePersistenceConsumer.java  ← DB 저장 담당
│   ├── MessageBroadcastConsumer.java    ← 실시간 전달 담당
│   └── ReadReceiptConsumer.java         ← 읽음 처리 담당
│
├── service/                    ← 비즈니스 로직
│   ├── AuthService.java        ← 회원가입, 로그인
│   ├── ChatRoomService.java    ← 채팅방 생성, 조회
│   ├── MessageService.java     ← 메시지 이력 조회
│   ├── ReadReceiptService.java ← 읽음 처리
│   └── RedisPubSubService.java ← Redis 발행/구독 → WebSocket 전달
│
└── controller/                 ← API 엔드포인트
    ├── AuthController.java     ← POST /api/auth/signup, /login
    ├── ChatRoomController.java ← POST/GET /api/rooms/**
    ├── MessageController.java  ← GET /api/rooms/{id}/messages
    └── ChatMessageController.java ← WebSocket @MessageMapping
```

### DTO vs Entity — 왜 분리하는가?

```
[Entity]
DB 테이블과 1:1 매핑. password 같은 민감 정보도 있음.
절대 클라이언트에게 그대로 보내면 안 됨!

[DTO (Data Transfer Object)]
클라이언트와 주고받는 형식만 정의.
필요한 필드만 골라서 응답.

예시:
User Entity:   { id, email, PASSWORD, nickname, status, lastSeenAt, createdAt }
AuthResponse:  { token, userId, email, nickname }  ← password 제외!
```

---

## 4. STEP 1: 프로젝트 뼈대

### build.gradle.kts — 의존성 관리

프로젝트에서 사용하는 라이브러리를 선언하는 파일입니다.

```kotlin
plugins {
    java
    id("org.springframework.boot") version "3.4.3"  // Spring Boot 플러그인
    id("io.spring.dependency-management") version "1.1.7"  // 버전 자동 관리
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)  // Java 21 사용
    }
}
```

주요 의존성 설명:
```
spring-boot-starter-web        → REST API 서버 (HTTP 요청 처리)
spring-boot-starter-websocket  → WebSocket/STOMP 서버
spring-boot-starter-data-jpa   → DB 연동 (Entity ↔ 테이블 매핑)
spring-boot-starter-data-redis → Redis 연동
spring-boot-starter-security   → 인증/인가 (JWT 필터 연결)
spring-boot-starter-validation → 입력값 검증 (@NotBlank, @Email 등)
spring-kafka                   → Kafka Producer/Consumer
jjwt                          → JWT 토큰 생성/검증 라이브러리
postgresql                    → PostgreSQL DB 드라이버
lombok                        → 반복 코드 줄여줌 (@Getter 등)
testcontainers                → 테스트용 Docker 자동 관리
```

### docker-compose.yml — 인프라 구성

```yaml
services:
  postgres:       # DB. 채팅방, 유저, 메시지 영구 저장
    image: postgres:16-alpine
    ports: ["5432:5432"]

  redis:          # 캐시 + Pub/Sub. 실시간 브로드캐스트
    image: redis:7-alpine
    ports: ["6379:6379"]

  kafka:          # 메시지 큐. 순서 보장, Consumer Group
    image: apache/kafka:3.9.0
    ports: ["29092:29092"]   # 로컬에서 접속하는 포트
    # KRaft 모드: Zookeeper 없이 Kafka 단독 실행

  kafka-ui:       # 웹 브라우저에서 Kafka 토픽/메시지 확인
    ports: ["8090:8080"]     # http://localhost:8090 접속
```

**KRaft 모드란?**
```
이전 Kafka: Kafka + Zookeeper(클러스터 관리) 두 개를 같이 실행해야 했음
KRaft 모드: Kafka가 스스로 클러스터를 관리 → Zookeeper 불필요
→ 운영이 간단해짐
```

### application.yml — 앱 설정

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/chat  # DB 접속 정보
  jpa:
    hibernate:
      ddl-auto: validate  # Entity와 테이블이 일치하는지 검증만 (자동 생성 안 함)
    open-in-view: false   # 성능을 위해 끄기 (Lazy Loading 범위 제한)
  kafka:
    bootstrap-servers: localhost:29092  # Kafka 접속 주소
    consumer:
      enable-auto-commit: false  # 수동 커밋 (메시지 유실 방지)

jwt:
  secret: realtime-chat-jwt-secret-key-must-be-at-least-256-bits-long
  expiration: 86400000  # 24시간 (밀리초)
```

**`ddl-auto: validate`가 뭐야?**
```
create:        앱 시작 시 테이블 생성 (기존 데이터 삭제)
create-drop:   앱 시작 시 생성, 종료 시 삭제 (테스트용)
update:        Entity 변경 시 테이블 수정 (위험할 수 있음)
validate:      Entity와 테이블이 맞는지만 확인 (우리 선택)
none:          아무것도 안 함

→ validate를 쓰고, 테이블은 schema.sql로 직접 관리
→ 프로덕션에서는 이 방식이 안전함
```

### schema.sql — 테이블 생성

```sql
-- messages 테이블의 핵심 컬럼들
CREATE TABLE IF NOT EXISTS messages (
    id              BIGSERIAL PRIMARY KEY,     -- 자동 증가 PK
    message_key     UUID NOT NULL UNIQUE,       -- 멱등성 보장용 (같은 메시지 중복 저장 방지)
    room_id         BIGINT NOT NULL,            -- 어느 방의 메시지인지
    sender_id       BIGINT NOT NULL,            -- 누가 보냈는지
    content         TEXT NOT NULL,              -- 메시지 내용
    kafka_partition INT,                        -- Kafka 파티션 번호 (디버깅용)
    kafka_offset    BIGINT,                     -- Kafka 오프셋 (디버깅용)
    created_at      TIMESTAMP NOT NULL DEFAULT NOW()
);

-- 복합 인덱스: 채팅방별 메시지를 빠르게 조회
CREATE INDEX IF NOT EXISTS idx_messages_room_id_id ON messages(room_id, id DESC);
```

**인덱스가 왜 필요해?**
```
인덱스 없이: "1번 방의 최신 메시지 20개 보여줘" → 전체 메시지 100만 건 다 뒤짐
인덱스 있음: room_id로 바로 찾고, id DESC로 최신 순 → 거의 즉시

비유: 책에서 "Kafka" 찾기
인덱스 없음: 1쪽부터 끝까지 다 읽음
인덱스 있음: 맨 뒤 색인에서 "Kafka → 42쪽" 바로 찾음
```

---

## 5. STEP 2: 데이터베이스 설계

### Entity — DB 테이블과 Java 클래스 연결

**User.java** — 사용자
```java
@Entity                           // "이 클래스는 DB 테이블이야"
@Table(name = "users")            // "테이블 이름은 users"
@Getter                           // Lombok: getId(), getEmail() 등 자동 생성
@NoArgsConstructor(access = PROTECTED)  // JPA가 내부적으로 쓰는 기본 생성자
public class User {

    @Id                           // Primary Key
    @GeneratedValue(strategy = GenerationType.IDENTITY)  // DB가 자동 증가 (BIGSERIAL)
    private Long id;

    @Column(nullable = false, unique = true)  // NOT NULL + UNIQUE 제약
    private String email;

    @Enumerated(EnumType.STRING)  // DB에 "ONLINE"/"OFFLINE" 문자열로 저장
    private UserStatus status = UserStatus.OFFLINE;

    // 생성자: new User("a@b.com", "encoded_pw", "닉네임") 이렇게 생성
    public User(String email, String password, String nickname) { ... }

    @PrePersist  // DB에 저장(INSERT)되기 직전에 자동 실행
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();  // 생성 시각 자동 기록
    }
}
```

**ChatRoom.java** — 채팅방
```java
@Entity
public class ChatRoom {
    private String name;                        // 방 이름 (1:1은 null)
    private RoomType type;                      // DIRECT(1:1) or GROUP(그룹)

    @ManyToOne(fetch = FetchType.LAZY)          // 방 1개 → 생성자 1명
    private User createdBy;                     // 누가 만들었는지

    @OneToMany(mappedBy = "chatRoom", cascade = CascadeType.ALL)
    private List<ChatRoomMember> members;       // 방 멤버 목록

    public void addMember(User user) {          // 멤버 추가 메서드
        ChatRoomMember member = new ChatRoomMember(this, user);
        this.members.add(member);
    }
}
```

**`FetchType.LAZY`가 뭐야?**
```
EAGER: ChatRoom을 조회하면 createdBy(User)도 무조건 같이 조회 (JOIN)
LAZY:  ChatRoom만 조회하고, createdBy는 실제로 접근할 때 조회

예: 채팅방 100개 목록에서 생성자 정보가 필요 없다면?
EAGER: 100개 방 + 100명 유저 = 불필요한 쿼리
LAZY:  100개 방만 조회 = 효율적
```

**Message.java** — 메시지
```java
@Entity
public class Message {
    private UUID messageKey;      // 멱등성 키 (중복 저장 방지)
    private ChatRoom chatRoom;    // 어느 방의 메시지
    private User sender;          // 보낸 사람
    private String content;       // 내용
    private MessageType type;     // TEXT / IMAGE / SYSTEM
    private Integer kafkaPartition;  // Kafka 메타데이터
    private Long kafkaOffset;        // Kafka 메타데이터
}
```

**멱등성(Idempotency)이란?**
```
문제 상황:
1. Consumer가 메시지를 DB에 저장
2. 저장은 됐는데, Kafka에 "처리 완료" 알리기 전에 서버가 죽음
3. 서버 재시작 → Kafka가 같은 메시지를 다시 보냄
4. 같은 메시지가 2번 저장됨!

해결:
각 메시지에 UUID(messageKey)를 부여하고, DB에 UNIQUE 제약
→ 같은 messageKey로 INSERT 시도하면 DB가 거부
→ "이미 저장된 메시지네" → 스킵
```

### Repository — DB CRUD 인터페이스

```java
public interface MessageRepository extends JpaRepository<Message, Long> {

    // 메서드 이름만 정의하면 Spring이 자동으로 SQL 생성!
    boolean existsByMessageKey(UUID messageKey);
    // → SELECT EXISTS(SELECT 1 FROM messages WHERE message_key = ?)

    // 복잡한 쿼리는 JPQL(JPA 전용 SQL)로 직접 작성
    @Query("""
            SELECT m FROM Message m
            JOIN FETCH m.sender
            WHERE m.chatRoom.id = :roomId AND m.id < :cursor
            ORDER BY m.id DESC
            LIMIT :limit
            """)
    List<Message> findByRoomIdWithCursor(...);
}
```

**JpaRepository가 자동으로 제공하는 메서드들:**
```
save(entity)        → INSERT 또는 UPDATE
findById(id)        → SELECT WHERE id = ?
findAll()           → SELECT * FROM ...
deleteById(id)      → DELETE WHERE id = ?
count()             → SELECT COUNT(*) FROM ...
```

---

## 6. STEP 3: JWT 인증

### 인증 흐름 전체 그림

```
[회원가입]
POST /api/auth/signup { email, password, nickname }
    → 비밀번호 BCrypt 암호화
    → DB에 User 저장
    → JWT 토큰 생성
    ← { token: "eyJ...", userId: 1, email: "...", nickname: "..." }

[로그인]
POST /api/auth/login { email, password }
    → DB에서 이메일로 User 조회
    → BCrypt로 비밀번호 비교
    → JWT 토큰 생성
    ← { token: "eyJ...", ... }

[이후 모든 요청]
GET /api/rooms
Headers: Authorization: Bearer eyJ...
    → JwtAuthenticationFilter가 토큰 검증
    → SecurityContext에 userId 저장
    → Controller에서 @AuthenticationPrincipal Long userId로 접근
```

### JwtTokenProvider.java — 토큰 생성/검증

```java
@Component
public class JwtTokenProvider {
    private final SecretKey key;     // HMAC 서명에 쓰는 비밀키
    private final long expiration;   // 만료 시간

    // 토큰 생성: userId + email을 담아서 서명
    public String createToken(Long userId, String email) {
        return Jwts.builder()
                .subject(String.valueOf(userId))   // "sub": "1"
                .claim("email", email)             // "email": "a@b.com"
                .issuedAt(now)                     // 발행 시각
                .expiration(new Date(now + expiration))  // 만료 시각
                .signWith(key)                     // 비밀키로 서명
                .compact();                        // 문자열로 변환
    }

    // 토큰에서 userId 추출
    public Long getUserId(String token) {
        Claims claims = parseClaims(token);  // 서명 검증 + 파싱
        return Long.parseLong(claims.getSubject());
    }

    // 유효성 검증 (서명 위변조, 만료 등)
    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;  // 위변조, 만료, 형식 오류
        }
    }
}
```

### JwtAuthenticationFilter.java — 매 요청마다 토큰 확인

```java
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {
    // OncePerRequestFilter: 요청 1번에 딱 1번만 실행되는 필터

    @Override
    protected void doFilterInternal(request, response, filterChain) {
        // 1. 헤더에서 토큰 추출: "Authorization: Bearer eyJ..." → "eyJ..."
        String token = resolveToken(request);

        // 2. 토큰 유효하면 SecurityContext에 인증 정보 저장
        if (token != null && jwtTokenProvider.validateToken(token)) {
            Long userId = jwtTokenProvider.getUserId(token);
            // Principal = userId, Credentials = null, Authorities = 빈 리스트
            UsernamePasswordAuthenticationToken authentication =
                    new UsernamePasswordAuthenticationToken(userId, null, List.of());
            SecurityContextHolder.getContext().setAuthentication(authentication);
        }

        // 3. 다음 필터로 넘김 (이게 없으면 요청 처리가 안 됨!)
        filterChain.doFilter(request, response);
    }
}
```

**Spring Security 필터 체인:**
```
HTTP 요청 → [Filter 1] → [Filter 2] → ... → [JwtAuthFilter] → ... → [Controller]

우리의 JwtAuthFilter는 UsernamePasswordAuthenticationFilter "앞에" 배치:
.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
→ 기본 로그인 폼 대신 JWT 방식 사용
```

### SecurityConfig.java — 보안 설정

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        return http
                .csrf(AbstractHttpConfigurer::disable)     // REST API는 CSRF 불필요
                .sessionManagement(session ->
                        session.sessionCreationPolicy(STATELESS))  // 세션 사용 안 함 (JWT!)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/auth/**").permitAll()  // 로그인/가입은 토큰 불필요
                        .requestMatchers("/ws/**").permitAll()         // WebSocket은 별도 인증
                        .anyRequest().authenticated())                 // 나머지는 토큰 필수
                .addFilterBefore(jwtAuthFilter, ...)  // JWT 필터 등록
                .build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();  // 비밀번호 암호화
    }
}
```

**CSRF를 왜 끄나?**
```
CSRF(Cross-Site Request Forgery): 다른 사이트에서 우리 서버에 요청을 위조하는 공격
브라우저의 쿠키를 이용한 공격인데, JWT는 쿠키가 아니라 Header로 전달하므로 CSRF 위험 없음
→ REST API + JWT 조합에서는 CSRF 보호가 불필요
```

**BCrypt란?**
```
"password123" → BCrypt → "$2a$10$N9qo8uLOickgx2ZMRZoMye..."

특징:
- 같은 비밀번호도 매번 다른 해시 생성 (salt가 랜덤)
- 단방향: 해시에서 원본 비밀번호 복원 불가능
- 의도적으로 느림 → 브루트포스 공격 방어
```

---

## 7. STEP 4: 채팅방 CRUD

### 1:1 채팅방 — 중복 방지가 핵심

```java
// ChatRoomService.java
public ChatRoomResponse createDirectRoom(Long userId, CreateDirectRoomRequest request) {
    // 자기 자신과의 채팅방 방지
    if (userId.equals(request.getTargetUserId())) {
        throw new BusinessException(BAD_REQUEST, "자기 자신과의 채팅방은 생성할 수 없습니다.");
    }

    // 핵심: 기존 1:1 방이 있으면 새로 만들지 않고 기존 방 반환
    return chatRoomRepository.findDirectRoomByUsers(DIRECT, userId, targetUserId)
            .map(ChatRoomResponse::from)     // 기존 방 있으면 → 그대로 반환
            .orElseGet(() -> {               // 없으면 → 새로 생성
                ChatRoom room = new ChatRoom(null, DIRECT, currentUser);
                room.addMember(currentUser);
                room.addMember(targetUser);
                chatRoomRepository.save(room);
                return ChatRoomResponse.from(room);
            });
}
```

**왜 중복 방지?**
```
카카오톡 생각해보면:
A가 B에게 1:1 채팅 시작 → 방1 생성
B가 A에게 1:1 채팅 시작 → 방1을 열어줘야지, 방2를 만들면 안 됨!
```

**기존 방 찾는 쿼리:**
```java
@Query("""
    SELECT cr FROM ChatRoom cr
    WHERE cr.type = :type                              -- DIRECT 타입이고
    AND cr.id IN (SELECT m1.chatRoom.id FROM ChatRoomMember m1
                  WHERE m1.user.id = :userId1)         -- 유저1이 참여하고
    AND cr.id IN (SELECT m2.chatRoom.id FROM ChatRoomMember m2
                  WHERE m2.user.id = :userId2)         -- 유저2도 참여한 방
    """)
Optional<ChatRoom> findDirectRoomByUsers(...);
```

### @AuthenticationPrincipal — 현재 로그인한 유저 가져오기

```java
@PostMapping("/direct")
public ResponseEntity<ChatRoomResponse> createDirectRoom(
        @AuthenticationPrincipal Long userId,  // ← SecurityContext에서 자동 추출!
        @RequestBody CreateDirectRoomRequest request) {
    return ResponseEntity.status(CREATED)
            .body(chatRoomService.createDirectRoom(userId, request));
}
```

**어떻게 동작하는 거야?**
```
1. 클라이언트가 요청: "Authorization: Bearer eyJ..."
2. JwtAuthenticationFilter가 토큰에서 userId(1) 추출
3. SecurityContext에 저장: authentication.principal = 1L
4. Controller의 @AuthenticationPrincipal Long userId = 1L 자동 주입!
```

---

## 8. STEP 5: Kafka 메시지 파이프라인

### KafkaConfig.java — 토픽과 Consumer 설정

```java
@Configuration
public class KafkaConfig {
    // 토픽 정의
    public static final String MESSAGES_TOPIC = "chat.messages";         // 채팅 메시지
    public static final String READ_RECEIPTS_TOPIC = "chat.read-receipts"; // 읽음 처리
    public static final String MESSAGES_DLT = "chat.messages.dlt";       // 실패 메시지 격리

    // 토픽 생성 (앱 시작 시 자동)
    @Bean
    public NewTopic messagesTopic() {
        return TopicBuilder.name(MESSAGES_TOPIC)
                .partitions(6)    // 6개 파티션 (최대 6대 Consumer 병렬 처리)
                .replicas(1)      // 복제본 1개 (개발 환경)
                .build();
    }

    // Consumer 설정
    private ConcurrentKafkaListenerContainerFactory<...> createListenerFactory(String groupId) {
        factory.getContainerProperties().setAckMode(AckMode.MANUAL);  // 수동 커밋!

        // 에러 핸들러: 1초 간격으로 3회 재시도, 그래도 실패하면 포기 (DLT로 감)
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(new FixedBackOff(1000L, 3));
        factory.setCommonErrorHandler(errorHandler);
        return factory;
    }
}
```

**수동 커밋(Manual Commit)이 왜 중요해?**
```
[자동 커밋의 문제]
1. Consumer가 메시지 받음
2. Kafka에 자동으로 "받았어!" (커밋)
3. DB 저장하다가 실패
4. 메시지 유실! (Kafka는 이미 "처리됨"으로 기록)

[수동 커밋]
1. Consumer가 메시지 받음
2. DB 저장 성공
3. 그제서야 Kafka에 "처리 완료!" (ack.acknowledge())
4. 만약 3번 전에 서버가 죽으면? → Kafka가 다시 보내줌 → 안전!
```

**DLT(Dead Letter Topic)란?**
```
3번 재시도해도 실패하는 메시지가 있을 수 있음
(예: 메시지 형식이 잘못됨, 참조하는 채팅방이 삭제됨)

이런 메시지를 계속 재시도하면 뒤의 정상 메시지도 처리가 안 됨!
→ 실패 메시지를 별도 토픽(DLT)에 격리
→ 나중에 관리자가 확인/처리
```

### ChatMessageProducer.java — Kafka에 메시지 보내기

```java
@Component
public class ChatMessageProducer {

    public void sendMessage(ChatMessageEvent event) {
        String key = String.valueOf(event.getRoomId());  // partition key = roomId!
        kafkaTemplate.send(MESSAGES_TOPIC, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("발행 실패", ex);
                    } else {
                        log.debug("발행 성공: partition={}, offset={}",
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }
}
```

**`whenComplete`는 뭐야?**
```
kafkaTemplate.send()는 비동기로 동작
→ "보내놓고 결과는 나중에 확인" (논블로킹)
→ whenComplete: 전송 완료 시 콜백 실행
→ 성공이면 로깅, 실패면 에러 로깅
```

### MessagePersistenceConsumer.java — DB 저장 (Consumer Group 1)

```java
@Component
public class MessagePersistenceConsumer {

    @KafkaListener(
            topics = MESSAGES_TOPIC,
            containerFactory = "persistenceListenerFactory"  // chat-persistence 그룹
    )
    @Transactional  // DB 작업이므로 트랜잭션 필요
    public void consume(ConsumerRecord<String, ChatMessageEvent> record, Acknowledgment ack) {
        ChatMessageEvent event = record.value();

        // 멱등성 체크: 이미 저장된 메시지면 스킵
        if (messageRepository.existsByMessageKey(event.getMessageKey())) {
            log.info("중복 메시지 스킵: messageKey={}", event.getMessageKey());
            ack.acknowledge();  // "처리 완료" 표시 (스킵이지만 처리는 한 거니까)
            return;
        }

        // 메시지 저장
        Message message = new Message(event.getMessageKey(), room, sender, ...);
        message.updateKafkaMetadata(record.partition(), record.offset());
        messageRepository.save(message);

        // 발신자를 제외한 멤버들의 안읽은 메시지 수 증가
        chatRoomMemberRepository.incrementUnreadCountForOtherMembers(roomId, senderId);

        ack.acknowledge();  // 모든 처리가 끝난 후에만 커밋!
    }
}
```

**@Transactional이 왜 필요해?**
```
한 Consumer가 하는 일:
1. 메시지 DB 저장 (INSERT INTO messages)
2. unreadCount 증가 (UPDATE chat_room_members)

만약 1은 성공하고 2가 실패하면?
→ 메시지는 있는데 안읽은 수는 안 올라감 → 데이터 불일치!

@Transactional: 1과 2를 하나의 묶음으로 실행
→ 하나라도 실패하면 전부 취소 (롤백)
→ 전부 성공해야 최종 반영 (커밋)
```

---

## 9. STEP 6: WebSocket + Redis Pub/Sub

### WebSocketConfig.java — STOMP 설정

```java
@Configuration
@EnableWebSocketMessageBroker  // WebSocket 메시지 브로커 활성화
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");  // 서버→클라이언트 구독 prefix
        registry.setApplicationDestinationPrefixes("/app");  // 클라이언트→서버 prefix
    }

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint("/ws")           // WebSocket 연결 URL
                .setAllowedOriginPatterns("*");  // 모든 도메인 허용 (개발용)
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor);  // 인증 인터셉터 등록
    }
}
```

**클라이언트 입장에서 사용법:**
```javascript
// 1. WebSocket 연결 (STOMP CONNECT)
const socket = new WebSocket("ws://localhost:8080/ws");
const stompClient = Stomp.over(socket);
stompClient.connect(
    { Authorization: "Bearer eyJ..." },  // JWT 토큰
    () => {
        // 2. 채팅방 구독
        stompClient.subscribe("/topic/room.1", (message) => {
            console.log("새 메시지:", JSON.parse(message.body));
        });

        // 3. 메시지 전송
        stompClient.send("/app/chat.send", {},
            JSON.stringify({ roomId: 1, content: "안녕하세요!" }));
    }
);
```

### WebSocketAuthInterceptor.java — STOMP 연결 시 JWT 검증

```java
@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = ...;

        // STOMP CONNECT 프레임에서만 인증 (최초 연결 시)
        if (CONNECT.equals(accessor.getCommand())) {
            String authHeader = accessor.getFirstNativeHeader("Authorization");

            if (authHeader != null && authHeader.startsWith("Bearer ")) {
                String token = authHeader.substring(7);
                if (jwtTokenProvider.validateToken(token)) {
                    Long userId = jwtTokenProvider.getUserId(token);
                    // WebSocket 세션에 userId 바인딩
                    accessor.setUser(new UsernamePasswordAuthenticationToken(userId, null, ...));
                } else {
                    throw new IllegalArgumentException("유효하지 않은 토큰입니다.");
                }
            }
        }
        return message;
    }
}
```

**왜 HTTP 필터가 아니라 별도 인터셉터를 쓰나?**
```
HTTP 요청: JwtAuthenticationFilter가 처리 (Spring Security 필터 체인)
WebSocket: HTTP 업그레이드 후에는 Spring Security 필터를 안 탐!
→ STOMP 레벨에서 별도로 인증 처리 필요
→ ChannelInterceptor에서 CONNECT 프레임의 Authorization 헤더 검증
```

### ChatMessageController.java — WebSocket 메시지 수신

```java
@Controller
public class ChatMessageController {

    // /app/chat.send로 온 메시지를 처리
    @MessageMapping("/chat.send")
    public void sendMessage(@Payload SendMessageRequest request, Principal principal) {
        Long userId = Long.parseLong(principal.getName());  // 인터셉터에서 설정한 userId

        // 채팅방 멤버인지 확인
        if (!chatRoomMemberRepository.existsByChatRoomIdAndUserId(request.getRoomId(), userId)) {
            throw new BusinessException(FORBIDDEN, "채팅방에 참여하지 않은 사용자입니다.");
        }

        // Kafka 이벤트 생성 및 발행
        ChatMessageEvent event = ChatMessageEvent.of(
                request.getRoomId(), userId, sender.getNickname(),
                request.getContent(), request.getType()
        );
        chatMessageProducer.sendMessage(event);  // Kafka로!
        // ↑ 여기서 바로 클라이언트에게 보내지 않음!
        // Kafka Consumer가 처리한 후 Redis Pub/Sub → WebSocket으로 전달됨
    }
}
```

**왜 바로 안 보내고 Kafka를 거치나?**
```
[바로 보내는 방식]
클라이언트A → 서버 → 바로 클라이언트B에게 전달 + 별도로 DB 저장
문제: DB 저장과 전달 순서가 뒤바뀔 수 있음, 서버 2대면 복잡해짐

[Kafka 경유 방식]
클라이언트A → 서버 → Kafka → Consumer1(DB 저장) + Consumer2(브로드캐스트)
장점: Kafka가 순서를 보장, 모든 처리가 동일한 순서로 진행
단점: 약간의 지연 (수 ms) → 채팅에서는 감지 안 될 정도
```

### RedisPubSubService.java — 서버 간 브로드캐스트

```java
@Service
public class RedisPubSubService {

    // Kafka Consumer → Redis 채널에 발행
    public void publish(ChatMessageEvent event) {
        String channel = "chat:room:" + event.getRoomId();  // 예: "chat:room:1"
        String message = objectMapper.writeValueAsString(event);
        redisTemplate.convertAndSend(channel, message);
    }

    // Redis 구독 → WebSocket으로 클라이언트에게 전달
    public void onMessage(String message, String channel) {
        ChatMessageEvent event = objectMapper.readValue(message, ChatMessageEvent.class);
        String roomId = channel.replace("chat:room:", "");
        String destination = "/topic/room." + roomId;  // STOMP 구독 경로

        messagingTemplate.convertAndSend(destination, event);
        // ↑ 이 서버에서 /topic/room.1을 구독 중인 모든 WebSocket 클라이언트에게 전달!
    }
}
```

**서버 2대일 때 Redis Pub/Sub의 역할:**
```
[서버 1]                           [서버 2]
사용자 A 연결                       사용자 B 연결
   │                                   │
   │    ┌──── Redis Pub/Sub ────┐      │
   │    │ "chat:room:1" 채널     │      │
   │    │   구독 ← 서버1         │      │
   │    │   구독 ← 서버2         │      │
   │    └──────────────────────┘      │
   │                                   │
Consumer가 Redis에 publish             │
→ 서버1: onMessage() → 사용자A에게 전달  │
→ 서버2: onMessage() → 사용자B에게 전달 ←┘
```

### MessageBroadcastConsumer.java — Kafka → Redis 연결

```java
// Consumer Group 2: Kafka에서 받은 메시지를 Redis Pub/Sub로 발행
@Component
public class MessageBroadcastConsumer {

    @KafkaListener(topics = MESSAGES_TOPIC, containerFactory = "broadcastListenerFactory")
    public void consume(ConsumerRecord<String, ChatMessageEvent> record, Acknowledgment ack) {
        ChatMessageEvent event = record.value();
        redisPubSubService.publish(event);  // Redis Pub/Sub로 전달
        ack.acknowledge();
    }
}
```

**Consumer 2개가 같은 토픽을 읽는 이유:**
```
chat.messages 토픽
    │
    ├── Consumer Group 1 (chat-persistence): 메시지를 DB에 저장
    │   → 영구 보관 목적
    │
    └── Consumer Group 2 (chat-broadcast): 메시지를 실시간 전달
        → 현재 접속 중인 사용자에게 즉시 전달

서로 다른 Consumer Group이므로 같은 메시지를 각각 받음!
```

---

## 10. STEP 7: 메시지 이력 + 읽음 처리

### 커서 기반 페이지네이션

**왜 offset이 아니라 cursor?**
```
[Offset 방식] "20번째부터 20개 보여줘"
SELECT * FROM messages WHERE room_id = 1 ORDER BY id DESC OFFSET 20 LIMIT 20;
문제: 새 메시지가 추가되면 OFFSET이 밀림 → 같은 메시지가 중복으로 보임

[Cursor 방식] "이 메시지(id=50) 이전 것 20개 보여줘"
SELECT * FROM messages WHERE room_id = 1 AND id < 50 ORDER BY id DESC LIMIT 20;
장점: 새 메시지가 추가되어도 영향 없음, 인덱스 효율적
```

```java
// MessageService.java
public MessagePageResponse getMessages(Long userId, Long roomId, Long cursor, int size) {
    int fetchSize = size + 1;  // 1개 더 조회해서 hasMore 판단!

    List<Message> messages;
    if (cursor == null) {
        messages = messageRepository.findByRoomIdLatest(roomId, fetchSize);  // 첫 페이지
    } else {
        messages = messageRepository.findByRoomIdWithCursor(roomId, cursor, fetchSize);
    }

    boolean hasMore = messages.size() > size;  // 21개 왔으면 더 있다!
    if (hasMore) {
        messages = messages.subList(0, size);  // 20개만 반환
    }

    Long nextCursor = hasMore ? messages.get(messages.size() - 1).getId() : null;
    return new MessagePageResponse(messageResponses, hasMore, nextCursor);
}
```

**size+1 트릭:**
```
요청: size=20
조회: LIMIT 21 (size+1)

결과가 21개 → "더 있다!" (hasMore=true), 마지막 1개의 id가 다음 cursor
결과가 15개 → "끝이다!" (hasMore=false), nextCursor=null
→ 별도의 COUNT 쿼리 없이 hasMore 판단 가능!
```

### 읽음 처리

```java
// ReadReceiptService.java

// 1. 클라이언트가 "여기까지 읽었어요" → Kafka로 발행
public void markAsRead(Long userId, Long roomId, Long lastReadMessageId) {
    ReadReceiptEvent event = ReadReceiptEvent.of(roomId, userId, lastReadMessageId);
    chatMessageProducer.sendReadReceipt(event);  // Kafka 토픽: chat.read-receipts
}

// 2. Kafka Consumer가 처리
@Transactional
public void processReadReceipt(ReadReceiptEvent event) {
    ChatRoomMember member = chatRoomMemberRepository
            .findByChatRoomIdAndUserId(event.getRoomId(), event.getUserId()).orElseThrow();

    // lastReadMessageId가 더 큰 값으로만 업데이트 (뒤로 가기 방지)
    if (member.getLastReadMessageId() == null ||
        event.getLastReadMessageId() > member.getLastReadMessageId()) {

        member.updateLastReadMessageId(event.getLastReadMessageId());

        // DB에서 실제 안읽은 수 재계산
        int unreadCount = messageRepository.countUnreadMessages(roomId, lastReadMessageId);
        member.updateUnreadCount(unreadCount);

        // Redis 캐시도 업데이트
        redisTemplate.opsForValue().set(key, String.valueOf(unreadCount));
    }
}

// 3. 안읽은 수 조회: Redis 먼저, 없으면 DB
public int getUnreadCount(Long roomId, Long userId) {
    String cached = redisTemplate.opsForValue().get(key);
    if (cached != null) return Integer.parseInt(cached);  // Redis 캐시 히트!

    // 캐시 미스 → DB 조회 → Redis에 저장
    ChatRoomMember member = chatRoomMemberRepository.findByChatRoomIdAndUserId(...);
    int unreadCount = member.getUnreadCount();
    redisTemplate.opsForValue().set(key, String.valueOf(unreadCount));
    return unreadCount;
}
```

**Redis 캐시 + DB fallback 패턴:**
```
[읽기]
1. Redis에서 찾기 → 있으면 바로 반환 (빠름!)
2. Redis에 없으면 → DB에서 조회 → Redis에 저장 → 반환

[쓰기]
1. DB 업데이트
2. Redis도 업데이트

[Redis 장애 시]
Redis가 죽어도 DB에 데이터 있으므로 서비스 가능
Redis는 순수 캐시 역할 → 장애 시 데이터 정합성 보장
```

---

## 11. STEP 8: 테스트

### BaseIntegrationTest — Testcontainers 설정

```java
@SpringBootTest(webEnvironment = RANDOM_PORT)  // 랜덤 포트로 실제 서버 실행
@ActiveProfiles("test")                         // application-test.yml 사용
@Testcontainers
public abstract class BaseIntegrationTest {

    // static: 모든 테스트 클래스가 같은 컨테이너 공유 (빠름!)
    static final PostgreSQLContainer<?> postgres = ...;
    static final KafkaContainer kafka = ...;
    static final GenericContainer<?> redis = ...;

    static {
        postgres.start();  // 클래스 로딩 시 1번만 실행
        kafka.start();
        redis.start();
    }

    @DynamicPropertySource  // application.yml의 설정값을 동적으로 덮어쓰기
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);     // 컨테이너 URL
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }
}
```

**왜 static?**
```
[static 아닐 때]
테스트 클래스 A: PostgreSQL 시작 → 테스트 → 종료
테스트 클래스 B: PostgreSQL 시작 → 테스트 → 종료
→ 매번 컨테이너를 띄우고 내림 → 느림!

[static일 때]
PostgreSQL 1번 시작 → 테스트 A → 테스트 B → 테스트 C → ... → 종료
→ 모든 테스트가 같은 컨테이너 사용 → 빠름!
```

### 단위 테스트 vs 통합 테스트

```
[단위 테스트] - Mockito로 의존성을 가짜로 대체
- 실제 DB, Kafka, Redis 없이 실행
- 빠름 (1초 이내)
- Service의 비즈니스 로직만 검증

[통합 테스트] - Testcontainers로 실제 인프라 사용
- 실제 DB에 데이터 저장, 실제 Kafka로 메시지 전달
- 느림 (수십 초)
- 전체 흐름이 정상 동작하는지 검증
```

**단위 테스트 예시:**
```java
@ExtendWith(MockitoExtension.class)  // Mockito 사용
class AuthServiceTest {
    @InjectMocks private AuthService authService;  // 테스트 대상
    @Mock private UserRepository userRepository;   // 가짜 DB
    @Mock private PasswordEncoder passwordEncoder; // 가짜 암호화
    @Mock private JwtTokenProvider jwtTokenProvider;// 가짜 JWT

    @Test
    void 회원가입_성공() {
        // given: 이런 상황이 주어졌을 때
        given(userRepository.existsByEmail("test@test.com")).willReturn(false);
        given(passwordEncoder.encode("password123")).willReturn("encoded");
        given(userRepository.save(any())).willReturn(savedUser);
        given(jwtTokenProvider.createToken(any(), anyString())).willReturn("jwt-token");

        // when: 이 메서드를 실행하면
        AuthResponse response = authService.signup(request);

        // then: 이런 결과가 나와야 한다
        assertThat(response.getToken()).isEqualTo("jwt-token");
        assertThat(response.getEmail()).isEqualTo("test@test.com");
    }
}
```

**통합 테스트 예시:**
```java
class AuthIntegrationTest extends BaseIntegrationTest {

    @Test
    void 회원가입_로그인_토큰인증_전체흐름() {
        // 1. 실제 HTTP 요청으로 회원가입
        ResponseEntity<AuthResponse> signup = restTemplate.postForEntity(
                "/api/auth/signup", signupRequest, AuthResponse.class);
        assertThat(signup.getStatusCode()).isEqualTo(CREATED);

        // 2. 실제 HTTP 요청으로 로그인
        ResponseEntity<AuthResponse> login = restTemplate.postForEntity(
                "/api/auth/login", loginRequest, AuthResponse.class);
        assertThat(login.getBody().getToken()).isNotBlank();

        // 3. 토큰으로 보호된 API 접근
        headers.setBearerAuth(login.getBody().getToken());
        ResponseEntity<String> rooms = restTemplate.exchange(
                "/api/rooms", GET, new HttpEntity<>(headers), String.class);
        assertThat(rooms.getStatusCode()).isEqualTo(OK);

        // 4. 토큰 없이 접근 → 401/403
        ResponseEntity<String> unauthorized = restTemplate.getForEntity("/api/rooms", String.class);
        assertThat(unauthorized.getStatusCode()).isIn(UNAUTHORIZED, FORBIDDEN);
    }
}
```

---

## 12. 메시지 전체 흐름 따라가기

사용자 A가 1번 채팅방에 "안녕하세요"를 보내면 어떤 일이 일어나는지
**코드 레벨**에서 처음부터 끝까지 따라갑니다.

### 1단계: 클라이언트 → WebSocket 서버

```
클라이언트가 STOMP 프레임 전송:
SEND
destination: /app/chat.send
content-type: application/json

{"roomId": 1, "content": "안녕하세요", "type": "TEXT"}
```

### 2단계: ChatMessageController 수신

```java
// /app 접두사 제거 → /chat.send와 매칭
@MessageMapping("/chat.send")
public void sendMessage(@Payload SendMessageRequest request, Principal principal) {
    Long userId = Long.parseLong(principal.getName());  // JWT에서 추출한 userId

    // 멤버 확인
    chatRoomMemberRepository.existsByChatRoomIdAndUserId(1L, userId);  // true

    // ChatMessageEvent 생성 (UUID 자동 생성)
    ChatMessageEvent event = ChatMessageEvent.of(1L, userId, "유저A", "안녕하세요", TEXT);
    // event.messageKey = "550e8400-e29b-41d4-a716-446655440000" (랜덤 UUID)

    chatMessageProducer.sendMessage(event);  // → Kafka로!
}
```

### 3단계: Kafka Producer 발행

```java
// ChatMessageProducer.java
kafkaTemplate.send("chat.messages", "1", event);
//                  토픽 이름,    key(roomId), 메시지

// Kafka 내부:
// key "1"을 해싱 → hash("1") % 6 = 3 → Partition 3에 저장
// Partition 3의 offset 42에 기록
```

### 4단계-A: MessagePersistenceConsumer (DB 저장)

```java
// Consumer Group "chat-persistence"
// Partition 3, Offset 42의 메시지를 받음

// 멱등성 체크
messageRepository.existsByMessageKey(UUID("550e8400..."))  // false (처음)

// DB 저장
Message message = new Message(UUID("550e8400..."), room, sender, "안녕하세요", TEXT);
message.updateKafkaMetadata(3, 42);  // partition=3, offset=42
messageRepository.save(message);  // INSERT INTO messages ...

// 안읽은 수 증가 (A를 제외한 나머지 멤버)
// UPDATE chat_room_members SET unread_count = unread_count + 1
// WHERE room_id = 1 AND user_id != A

ack.acknowledge();  // Kafka에 "처리 완료" 알림
```

### 4단계-B: MessageBroadcastConsumer (실시간 전달)

```java
// Consumer Group "chat-broadcast" (독립적으로 같은 메시지 수신)

redisPubSubService.publish(event);
// → Redis: PUBLISH "chat:room:1" "{\"messageKey\":\"550e8400...\", ...}"

ack.acknowledge();
```

### 5단계: Redis Pub/Sub → 모든 서버

```java
// RedisPubSubService.java (모든 서버 인스턴스에서 실행)
// Redis가 "chat:room:*" 패턴 구독자들에게 메시지 전달

public void onMessage(String message, String channel) {
    // channel = "chat:room:1"
    // message = JSON 문자열

    ChatMessageEvent event = objectMapper.readValue(message, ...);
    messagingTemplate.convertAndSend("/topic/room.1", event);
    // ↑ 이 서버에서 /topic/room.1을 구독 중인 모든 WebSocket 클라이언트에게 전달
}
```

### 6단계: WebSocket → 클라이언트

```
클라이언트가 이전에 구독한 /topic/room.1로 메시지 수신:

MESSAGE
destination: /topic/room.1
content-type: application/json

{
  "messageKey": "550e8400-e29b-41d4-a716-446655440000",
  "roomId": 1,
  "senderId": 1,
  "senderNickname": "유저A",
  "content": "안녕하세요",
  "type": "TEXT",
  "timestamp": "2026-02-07T12:00:00"
}
```

### 전체 정리

```
클라이언트A → [WebSocket] → ChatMessageController
                                  │
                            ChatMessageProducer
                                  │
                            [Kafka: chat.messages, Partition 3]
                                  │
                    ┌─────────────┴─────────────┐
                    ▼                             ▼
          PersistenceConsumer              BroadcastConsumer
          (chat-persistence)               (chat-broadcast)
                    │                             │
              DB INSERT                    Redis PUBLISH
              + unreadCount++              "chat:room:1"
                                                  │
                                    ┌─────────────┴─────────────┐
                                    ▼                             ▼
                              [서버 1]                       [서버 2]
                           onMessage()                    onMessage()
                                    │                             │
                         STOMP SEND                    STOMP SEND
                         /topic/room.1                 /topic/room.1
                                    │                             │
                              클라이언트B                    클라이언트C
```

---

## 13. 자주 묻는 질문

### Q: Entity에 `@Setter`를 안 쓰는 이유?

```java
// 나쁜 예
user.setEmail("new@email.com");  // 아무 데서나 값을 바꿀 수 있음 → 위험

// 좋은 예
user.updateStatus(UserStatus.ONLINE);  // 의미 있는 메서드로만 변경 가능

// @Setter를 쓰면 모든 필드를 외부에서 마음대로 바꿀 수 있어서
// 데이터 일관성이 깨질 위험이 있음.
// 대신 필요한 변경만 의미 있는 메서드로 제공.
```

### Q: `@NoArgsConstructor(access = PROTECTED)`는 왜?

```java
// JPA는 Entity를 DB에서 읽어올 때 기본 생성자(new User())가 필요함
// 하지만 외부에서 new User()로 빈 객체를 만드는 건 막고 싶음
// → PROTECTED: JPA(같은 패키지)는 접근 가능, 외부는 불가

@NoArgsConstructor(access = AccessLevel.PROTECTED)  // JPA 전용
public class User {
    public User(String email, String password, String nickname) { ... }  // 실제 사용하는 생성자
}
```

### Q: `FetchType.LAZY` vs `FetchType.EAGER`?

```
LAZY (우리 선택):
ChatRoom room = chatRoomRepository.findById(1);
// → SQL: SELECT * FROM chat_rooms WHERE id = 1 (방 정보만)
room.getCreatedBy().getNickname();
// → SQL: SELECT * FROM users WHERE id = ? (이때 유저 조회)

EAGER:
ChatRoom room = chatRoomRepository.findById(1);
// → SQL: SELECT * FROM chat_rooms cr JOIN users u ON cr.created_by = u.id
// → 항상 JOIN! 불필요할 때도 유저를 가져옴

LAZY가 기본적으로 성능이 좋음. 필요할 때만 추가 쿼리 실행.
단, 영속성 컨텍스트(트랜잭션) 밖에서 LAZY 접근하면 에러!
→ 이걸 막기 위해 open-in-view: false + Service에서 필요한 데이터를 미리 로딩
```

### Q: `@Transactional(readOnly = true)`의 효과?

```java
@Transactional(readOnly = true)  // 읽기 전용
public List<ChatRoomListResponse> getMyRooms(Long userId) { ... }

// 효과:
// 1. JPA Dirty Checking 비활성화 → 성능 향상
//    (변경 감지를 안 하니까 Entity 스냅샷을 안 찍음)
// 2. DB에 따라 읽기 전용 트랜잭션 최적화 가능
// 3. 실수로 데이터를 수정하는 버그 방지
```

### Q: `Optional`을 왜 쓰나?

```java
// Optional 없이 (NPE 위험)
User user = userRepository.findByEmail("test@test.com");
user.getNickname();  // user가 null이면 NullPointerException!

// Optional 사용 (안전)
userRepository.findByEmail("test@test.com")
        .orElseThrow(() -> new BusinessException(NOT_FOUND, "사용자를 찾을 수 없습니다."));
// → 없으면 명확한 에러 메시지
```

### Q: 왜 Kafka Consumer가 2개 그룹인가?

```
만약 1개 Consumer로 "DB 저장 + 브로드캐스트"를 동시에 하면?
→ DB 저장이 느려지면 브로드캐스트도 느려짐
→ 브로드캐스트 에러가 나면 DB 저장도 실패할 수 있음

Consumer Group을 분리하면:
→ DB 저장이 느려도 브로드캐스트는 독립적으로 동작
→ 각각의 처리 속도, 에러 핸들링, 스케일링이 독립적
→ 책임 분리 원칙 (Single Responsibility)
```

### Q: Redis Pub/Sub는 메시지를 저장하나?

```
아니요! Redis Pub/Sub는 "지금 구독 중인 서버"에게만 전달합니다.

서버가 꺼져있을 때 온 메시지 → 유실!
→ 그래서 DB 저장(Consumer Group 1)이 별도로 필요
→ 서버 재시작 후에는 DB에서 미수신 메시지를 가져오면 됨
   (GET /api/rooms/{roomId}/messages 커서 페이지네이션)
```

---

---

# 2차: 프로덕션 품질

> 1차(MVP)에서 "동작하는 코드"를 만들었다면, 2차에서는 **"운영 가능한 코드"**로 발전시킵니다.
> 실제 서비스에서 필요한 안정성, 보호, 모니터링, 스케일아웃을 추가합니다.

---

## 14. STEP 9: Health Check + Graceful Shutdown

### 왜 필요한가?

```
[문제 1: Health Check 없을 때]
로드 밸런서(Nginx, K8s): "서버 살아있어?" → 확인 방법 없음
→ 죽은 서버로 요청을 계속 보냄 → 장애 확산!

[해결] /actuator/health 엔드포인트
로드 밸런서: GET /actuator/health → {"status": "UP"} → 정상!
로드 밸런서: GET /actuator/health → 응답 없음 → 이 서버 제외!

[문제 2: Graceful Shutdown 없을 때]
서버 종료 명령 → 바로 프로세스 종료
→ 처리 중이던 메시지, DB 트랜잭션, WebSocket 연결 모두 끊김!

[해결] Graceful Shutdown
서버 종료 명령 → "새 요청은 거부, 기존 요청은 30초 내로 마무리" → 깔끔하게 종료
```

### application.yml 설정

```yaml
server:
  shutdown: graceful            # Graceful Shutdown 활성화

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s  # 진행 중인 요청 마무리 대기 시간

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics  # 노출할 Actuator 엔드포인트
  endpoint:
    health:
      show-details: always     # DB, Kafka, Redis 연결 상태까지 표시
      show-components: always
```

**Actuator 엔드포인트가 뭐야?**
```
Spring Boot가 기본 제공하는 운영 정보 API:

/actuator/health    → 서버 + DB + Kafka + Redis 상태 (UP/DOWN)
/actuator/info      → 앱 이름, 버전 등 기본 정보
/actuator/metrics   → JVM 메모리, HTTP 요청 수, GC 횟수 등 수치 데이터

health 응답 예시:
{
  "status": "UP",
  "components": {
    "db": { "status": "UP" },
    "kafka": { "status": "UP" },
    "redis": { "status": "UP" }
  }
}
```

### SecurityConfig.java — Health Check 허용

```java
.authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/auth/**").permitAll()
        .requestMatchers("/ws/**").permitAll()
        .requestMatchers("/actuator/health/**").permitAll()  // ← 추가!
        .anyRequest().authenticated())
```

**왜 permitAll?**
```
Health Check는 로드 밸런서/K8s가 호출하는데,
이들은 JWT 토큰이 없음 → 인증 없이 접근 가능해야 함

/actuator/info, /actuator/metrics는 인증 필요 (보안 정보 포함 가능)
→ authenticated() 그대로 유지
```

### Graceful Shutdown 동작 흐름

```
1. docker stop 또는 kill -15 (SIGTERM) 전송
2. Spring Boot가 SIGTERM 수신
3. 새 HTTP 요청/WebSocket 연결 거부
4. 진행 중인 요청 마무리 대기 (최대 30초)
5. Kafka Consumer 정상 종료 (offset 커밋)
6. DB 커넥션 풀 정리
7. 프로세스 종료

→ 메시지 유실 없이 깔끔하게 종료!
```

---

## 15. STEP 10: Rate Limiting

### 왜 필요한가?

```
[문제] 악의적 사용자가 1초에 10,000개 메시지를 전송하면?
→ Kafka에 메시지 폭탄 → Consumer 처리 지연 → 모든 사용자 채팅 느려짐
→ DB INSERT 폭주 → 디스크/CPU 과부하

[해결] 유저별 초당 메시지 수 제한
→ 기본 10개/초, 초과 시 STOMP ERROR 프레임 전송
→ 정상 사용자는 영향 없음 (사람이 1초에 10개 이상 타이핑 불가능)
```

### RateLimitInterceptor.java — 핵심 코드

```java
@Component
public class RateLimitInterceptor implements ChannelInterceptor {

    // 유저별 카운터 저장소 (서버 메모리)
    private final ConcurrentHashMap<Long, RateWindow> rateLimitMap = new ConcurrentHashMap<>();

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        // STOMP SEND 프레임만 체크 (CONNECT, SUBSCRIBE 등은 무시)
        if (!StompCommand.SEND.equals(accessor.getCommand())) {
            return message;
        }

        Long userId = Long.parseLong(user.getName());
        RateWindow window = rateLimitMap.computeIfAbsent(userId, k -> new RateWindow());

        if (!window.tryAcquire(messagesPerSecond)) {
            log.warn("Rate limit 초과: userId={}", userId);
            throw new IllegalStateException("메시지 전송 속도 제한을 초과했습니다.");
        }
        return message;
    }

    // 1초 슬라이딩 윈도우 카운터
    private static class RateWindow {
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicInteger count = new AtomicInteger(0);

        boolean tryAcquire(int limit) {
            long now = System.currentTimeMillis();

            // 1초 경과 → 카운터 리셋
            if (now - windowStart.get() >= 1000) {
                windowStart.set(now);
                count.set(1);
                return true;
            }

            // 제한 이내면 통과, 초과면 차단
            return count.incrementAndGet() <= limit;
        }
    }
}
```

**왜 ConcurrentHashMap?**
```
WebSocket 메시지는 여러 스레드에서 동시에 처리됨
→ 일반 HashMap을 쓰면 동시 접근 시 데이터가 깨질 수 있음
→ ConcurrentHashMap: 동시 접근에도 안전한 HashMap

AtomicInteger, AtomicLong도 같은 이유:
→ 여러 스레드가 동시에 count++ 해도 정확하게 동작
→ synchronized보다 가볍고 빠름
```

**슬라이딩 윈도우 vs 고정 윈도우:**
```
[고정 윈도우]
00:00:00 ~ 00:00:01 → 10개 허용
00:00:01 ~ 00:00:02 → 10개 허용
문제: 00:00:00.9에 10개, 00:00:01.0에 10개 → 0.1초에 20개!

[슬라이딩 윈도우 (우리 방식)]
"지금부터 1초 전까지" 윈도우로 판단
→ 1초 경과 시 카운터를 리셋
→ 간단하면서도 실용적인 방식
```

### WebSocketConfig.java — 인터셉터 등록

```java
@Override
public void configureClientInboundChannel(ChannelRegistration registration) {
    // 순서가 중요! 인증 → Rate Limiting
    registration.interceptors(webSocketAuthInterceptor, rateLimitInterceptor);
}
```

```
STOMP 메시지 도착
    │
    ▼
[WebSocketAuthInterceptor]  ← 1순위: JWT 검증 (누구인지 확인)
    │
    ▼
[RateLimitInterceptor]      ← 2순위: 속도 제한 (초당 10개 초과 시 차단)
    │
    ▼
[@MessageMapping 핸들러]    ← 통과한 메시지만 비즈니스 로직 실행
```

---

## 16. STEP 11: Consumer DLT 심화

### 1차에서의 문제점

```
[1차 구현]
DefaultErrorHandler + FixedBackOff(1000, 3)
→ 3번 재시도 후... 그냥 로그만 남기고 포기
→ 실패한 메시지가 어디에도 남지 않음!

[2차 개선]
DefaultErrorHandler + DeadLetterPublishingRecoverer + FixedBackOff(1000, 3)
→ 3번 재시도 후 → DLT 토픽에 메시지 격리 + 상세 에러 로깅
→ 실패 메시지를 나중에 확인/재처리 가능!
```

### DeadLetterPublishingRecoverer란?

```
정상 메시지:
chat.messages → Consumer 처리 성공 → DB 저장 완료

실패 메시지:
chat.messages → Consumer 처리 실패 (3회 재시도)
              → DeadLetterPublishingRecoverer가 chat.messages.dlt로 전송
              → 관리자가 나중에 확인

DLT = Dead Letter Topic (죽은 편지함)
= 배달 실패한 우편물을 보관하는 곳
```

### KafkaConfig.java — DLT 설정

```java
// DLT 토픽 생성
@Bean
public NewTopic messagesDltTopic() {
    return TopicBuilder.name(MESSAGES_DLT)    // "chat.messages.dlt"
            .partitions(1)                     // 실패 메시지용이므로 1개면 충분
            .replicas(1)
            .build();
}

@Bean
public NewTopic readReceiptsDltTopic() {
    return TopicBuilder.name(READ_RECEIPTS_DLT)  // "chat.read-receipts.dlt"
            .partitions(1)
            .replicas(1)
            .build();
}

// DLT Recoverer: 재시도 실패 시 원본 토픽 + ".dlt"로 전송
private DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaTemplate<String, Object> kafkaTemplate) {
    return new DeadLetterPublishingRecoverer(kafkaTemplate,
            (ConsumerRecord<?, ?> record, Exception ex) -> {
                log.error("DLT 전송: topic={}, partition={}, offset={}, error={}",
                        record.topic(), record.partition(), record.offset(), ex.getMessage());
                return new TopicPartition(
                        record.topic() + ".dlt",   // chat.messages → chat.messages.dlt
                        record.partition() % 1);    // DLT는 파티션 1개이므로 항상 0
            });
}

// 모든 Consumer Factory에 DLT 연결
private ConcurrentKafkaListenerContainerFactory<String, Object> createListenerFactory(
        String groupId, KafkaTemplate<String, Object> kafkaTemplate) {
    // ...
    DefaultErrorHandler errorHandler = new DefaultErrorHandler(
            deadLetterRecoverer(kafkaTemplate),  // ← DLT Recoverer 연결!
            new FixedBackOff(1000L, 3)           // 1초 간격, 3회 재시도
    );
    factory.setCommonErrorHandler(errorHandler);
    return factory;
}
```

**실패 처리 전체 흐름:**
```
메시지 도착 → Consumer 처리 시도
    │
    ├── 성공 → ack.acknowledge() → 다음 메시지 처리
    │
    └── 실패 → DefaultErrorHandler 개입
                │
                ├── 1회차 재시도 (1초 후) → 실패
                ├── 2회차 재시도 (1초 후) → 실패
                └── 3회차 재시도 (1초 후) → 실패
                    │
                    ▼
              DeadLetterPublishingRecoverer
              → chat.messages.dlt에 원본 메시지 + 에러 정보 저장
              → Kafka UI(localhost:8090)에서 DLT 토픽 확인 가능
```

### 에러 로깅 강화

```java
// MessagePersistenceConsumer.java
catch (Exception e) {
    log.error("메시지 저장 실패: messageKey={}, topic={}, partition={}, offset={}",
            event.getMessageKey(),
            record.topic(),        // ← 어느 토픽에서 왔는지
            record.partition(),    // ← 몇 번 파티션인지
            record.offset(), e);   // ← 몇 번째 메시지인지
    throw e;  // 다시 던져야 ErrorHandler가 재시도 + DLT 처리
}
```

**왜 topic, partition, offset을 로깅하나?**
```
"메시지 저장 실패: messageKey=abc-123"
→ 어떤 메시지인지는 알겠는데, 어디서 문제가 생겼는지 모름

"메시지 저장 실패: messageKey=abc-123, topic=chat.messages, partition=3, offset=42"
→ Kafka UI에서 partition 3, offset 42를 찾아서 원본 메시지 확인 가능
→ 디버깅 시간이 크게 단축됨
```

---

## 17. STEP 12: 온라인/오프라인 상태

### 전체 설계

```
사용자 A가 WebSocket 연결

[이벤트 감지]
SessionConnectEvent 발생
    │
    ▼
[WebSocketEventListener]
    │
    ├── PresenceService.setOnline(userId)     → Redis에 "user:presence:1" = "ONLINE" (TTL 60초)
    │
    └── RedisPubSubService.publishPresence()  → Redis "chat:presence" 채널에 발행
            │
            ▼
    [모든 서버 인스턴스]
    RedisPubSubService.onPresenceMessage()    → STOMP "/topic/presence"로 브로드캐스트
            │
            ▼
    [구독 중인 모든 클라이언트]
    { "userId": 1, "status": "ONLINE", "timestamp": 1707350400000 }
```

### PresenceEvent.java — 상태 이벤트 DTO

```java
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PresenceEvent {
    private Long userId;
    private String status;    // "ONLINE" or "OFFLINE"
    private long timestamp;

    // 팩토리 메서드: new 대신 의미 있는 이름으로 생성
    public static PresenceEvent online(Long userId) {
        return new PresenceEvent(userId, "ONLINE", System.currentTimeMillis());
    }

    public static PresenceEvent offline(Long userId) {
        return new PresenceEvent(userId, "OFFLINE", System.currentTimeMillis());
    }
}
```

### PresenceService.java — Redis 기반 상태 관리

```java
@Service
public class PresenceService {

    private static final String PRESENCE_KEY_PREFIX = "user:presence:";
    private static final Duration PRESENCE_TTL = Duration.ofSeconds(60);  // TTL 60초

    // 온라인 설정: Redis에 키 생성 + TTL
    public void setOnline(Long userId) {
        String key = PRESENCE_KEY_PREFIX + userId;      // "user:presence:1"
        redisTemplate.opsForValue().set(key, "ONLINE", PRESENCE_TTL);
    }

    // 오프라인 설정: Redis에서 키 삭제
    public void setOffline(Long userId) {
        String key = PRESENCE_KEY_PREFIX + userId;
        redisTemplate.delete(key);
    }

    // 온라인 여부 확인: 키가 존재하면 온라인
    public boolean isOnline(Long userId) {
        String key = PRESENCE_KEY_PREFIX + userId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    // 채팅방 멤버 중 온라인인 유저 목록
    public Set<Long> getOnlineMembers(Long roomId) {
        List<Long> memberUserIds = chatRoomMemberRepository.findAllByChatRoomId(roomId)
                .stream()
                .map(member -> member.getUser().getId())
                .collect(Collectors.toList());

        return memberUserIds.stream()
                .filter(this::isOnline)     // Redis에서 각 멤버의 온라인 상태 확인
                .collect(Collectors.toSet());
    }
}
```

**TTL(Time To Live)이 왜 60초?**
```
[문제] 서버가 비정상 종료되면?
→ SessionDisconnectEvent가 발생하지 않음
→ Redis에 "user:presence:1" = "ONLINE"이 영원히 남음
→ 오프라인인데 온라인으로 표시!

[해결] TTL 60초
→ 60초 후 자동 삭제 → 오프라인으로 자동 전환
→ 정상 연결 중이면? heartbeat로 TTL 갱신 (아직 미구현, 3차 예정)

비유: 출석 체크
"60초 안에 한 번은 출석 체크해야 함"
→ 체크 안 하면 퇴실 처리 (자동 오프라인)
```

### WebSocketEventListener.java — 연결/해제 감지

```java
@Component
public class WebSocketEventListener {

    // WebSocket 연결 시 → 온라인
    @EventListener
    public void handleWebSocketConnect(SessionConnectEvent event) {
        Long userId = extractUserId(event.getMessage().getHeaders().get("simpUser"));
        if (userId != null) {
            presenceService.setOnline(userId);                          // Redis 설정
            redisPubSubService.publishPresence(PresenceEvent.online(userId));  // 브로드캐스트
        }
    }

    // WebSocket 해제 시 → 오프라인
    @EventListener
    public void handleWebSocketDisconnect(SessionDisconnectEvent event) {
        Long userId = extractUserId(event.getUser());
        if (userId != null) {
            presenceService.setOffline(userId);                          // Redis 삭제
            redisPubSubService.publishPresence(PresenceEvent.offline(userId));  // 브로드캐스트
        }
    }

    // Principal에서 userId 추출
    private Long extractUserId(Object principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken auth) {
            return (Long) auth.getPrincipal();
            // WebSocketAuthInterceptor에서 설정한 인증 정보
        }
        return null;
    }
}
```

**@EventListener가 뭐야?**
```
Spring의 이벤트 시스템:
→ 특정 이벤트가 발생하면 자동으로 호출되는 메서드

SessionConnectEvent:    STOMP CONNECT 프레임 수신 시 발생
SessionDisconnectEvent: WebSocket 연결 끊김 시 발생 (클라이언트가 끊거나 네트워크 장애)

장점: WebSocket 코드를 수정하지 않고 이벤트만 감지해서 처리
→ 느슨한 결합 (Loose Coupling)
```

### RedisConfig.java — Presence 채널 구독

```java
@Configuration
public class RedisConfig {
    public static final String PRESENCE_CHANNEL = "chat:presence";

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter messageListenerAdapter,        // 채팅 메시지용
            MessageListenerAdapter presenceListenerAdapter) {     // Presence용 (추가!)
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        // 채팅 메시지: 패턴 구독 (chat:room:*)
        container.addMessageListener(messageListenerAdapter, new PatternTopic(CHAT_ROOM_PATTERN));
        // Presence: 정확한 채널 구독 (chat:presence)
        container.addMessageListener(presenceListenerAdapter, new ChannelTopic(PRESENCE_CHANNEL));
        return container;
    }

    @Bean
    public MessageListenerAdapter presenceListenerAdapter(RedisPubSubService redisPubSubService) {
        return new MessageListenerAdapter(redisPubSubService, "onPresenceMessage");
        //                                                     ↑ 이 메서드를 호출
    }
}
```

**PatternTopic vs ChannelTopic:**
```
PatternTopic("chat:room:*")
→ chat:room:1, chat:room:2, chat:room:999 등 모든 채팅방 채널 구독
→ 와일드카드 패턴 매칭

ChannelTopic("chat:presence")
→ 정확히 "chat:presence" 채널만 구독
→ Presence는 채널이 1개이므로 정확한 매칭 사용
```

### RedisPubSubService.java — Presence 브로드캐스트

```java
// Redis 채널에 Presence 이벤트 발행 (서버 간 상태 공유)
public void publishPresence(PresenceEvent event) {
    String message = objectMapper.writeValueAsString(event);
    redisTemplate.convertAndSend(RedisConfig.PRESENCE_CHANNEL, message);
    //                           "chat:presence"
}

// Redis에서 Presence 이벤트 수신 → STOMP로 전체 브로드캐스트
public void onPresenceMessage(String message, String channel) {
    PresenceEvent event = objectMapper.readValue(message, PresenceEvent.class);
    messagingTemplate.convertAndSend("/topic/presence", event);
    // 이 서버에서 /topic/presence를 구독 중인 모든 클라이언트에게 전달
}
```

**온라인 상태 전체 흐름 (서버 2대):**
```
사용자A가 서버1에 WebSocket 연결

[서버 1]
SessionConnectEvent → WebSocketEventListener
    → PresenceService.setOnline(1)         → Redis: SET user:presence:1 "ONLINE" EX 60
    → RedisPubSubService.publishPresence() → Redis: PUBLISH "chat:presence" {...}

[Redis Pub/Sub]
"chat:presence" 채널에 메시지 발행
    → 구독 중인 서버 1, 서버 2 모두에게 전달

[서버 1] onPresenceMessage()
    → messagingTemplate.convertAndSend("/topic/presence", event)
    → 서버1에 연결된 클라이언트들에게 전달

[서버 2] onPresenceMessage()
    → messagingTemplate.convertAndSend("/topic/presence", event)
    → 서버2에 연결된 클라이언트들에게 전달

결과: 어느 서버에 연결되어 있든 모든 클라이언트가 "사용자A 접속" 알림 수신!
```

### PresenceController.java — REST API

```java
@RestController
@RequestMapping("/api/rooms")
public class PresenceController {

    // GET /api/rooms/{roomId}/members/online → [1, 3, 5] (온라인 유저 ID 목록)
    @GetMapping("/{roomId}/members/online")
    public ResponseEntity<Set<Long>> getOnlineMembers(@PathVariable Long roomId) {
        return ResponseEntity.ok(presenceService.getOnlineMembers(roomId));
    }
}
```

```
클라이언트가 채팅방에 입장할 때:
1. WebSocket으로 /topic/presence 구독 → 실시간 상태 변경 수신
2. REST API로 현재 온라인 멤버 목록 조회 → 초기 상태 표시

이후: WebSocket으로 상태 변경만 수신 → REST API 호출 불필요 (효율적)
```

---

## 18. STEP 13: 스케일아웃 + 통합 테스트

### Dockerfile — Multi-stage 빌드

```dockerfile
# 1단계: 빌드 (JDK 필요)
FROM eclipse-temurin:21-jdk AS build
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true  # 의존성 캐싱
COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test  # JAR 생성 (테스트 스킵)

# 2단계: 실행 (JRE만 필요)
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

**Multi-stage 빌드가 왜 좋아?**
```
[단일 스테이지]
JDK(~400MB) + 소스 + Gradle + 빌드 결과 = ~800MB 이미지

[Multi-stage]
1단계(빌드): JDK + 소스 + Gradle → JAR 파일 생성
2단계(실행): JRE(~200MB) + JAR 파일만 = ~250MB 이미지
→ 3배 이상 이미지 크기 감소!
→ 소스 코드가 최종 이미지에 포함되지 않음 (보안)
```

**의존성 캐싱 트릭:**
```
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN ./gradlew dependencies                           ← 의존성만 먼저 다운로드

COPY src/ src/                                        ← 소스 코드 복사
RUN ./gradlew bootJar                                 ← 빌드

이렇게 분리하면:
소스 코드만 변경 시 → 의존성 레이어는 Docker 캐시에서 재사용
→ 빌드 시간 대폭 단축 (의존성 다시 다운로드 안 함)
```

### docker-compose.yml — 멀티 인스턴스

```yaml
# 애플리케이션 서버 (스케일아웃 검증용 2대)
app-1:
    build: .                    # 프로젝트 루트의 Dockerfile로 빌드
    container_name: chat-app-1
    environment:
      DB_HOST: postgres         # Docker 내부 네트워크에서 컨테이너 이름으로 접근
      DB_PORT: 5432
      REDIS_HOST: redis
      REDIS_PORT: 6379
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092  # Docker 내부에서는 9092 포트
    ports:
      - "8081:8080"             # 호스트 8081 → 컨테이너 8080
    depends_on:
      postgres:
        condition: service_healthy  # PostgreSQL 준비될 때까지 대기
      redis:
        condition: service_healthy
      kafka:
        condition: service_healthy

app-2:
    build: .
    container_name: chat-app-2
    environment:
      DB_HOST: postgres
      REDIS_HOST: redis
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    ports:
      - "8082:8080"             # 호스트 8082 → 컨테이너 8080
    depends_on: ...
```

**환경변수 fallback 패턴:**
```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/chat
  data:
    redis:
      host: ${REDIS_HOST:localhost}
  kafka:
    bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:localhost:29092}
```

```
${DB_HOST:localhost} 의미:
→ 환경변수 DB_HOST가 있으면 그 값 사용 (Docker: "postgres")
→ 없으면 기본값 "localhost" 사용 (로컬 개발)

이 패턴 덕분에 코드 변경 없이:
- 로컬: ./gradlew bootRun → localhost로 접속
- Docker: docker compose up → 환경변수로 오버라이드
```

**depends_on + service_healthy가 왜 중요해?**
```
[depends_on만 사용]
app-1 시작 → PostgreSQL 컨테이너 시작됨(하지만 DB 초기화 중)
→ app-1이 DB 연결 시도 → 실패! (아직 준비 안 됨)

[depends_on + condition: service_healthy]
PostgreSQL 시작 → healthcheck: pg_isready → "준비 완료!"
→ 그제서야 app-1 시작 → DB 연결 성공!
```

### 스케일아웃 시 Kafka Consumer Group 동작

```
[서버 1대일 때]
chat.messages 토픽 (6개 파티션)
Partition 0~5 → 서버1의 Consumer가 전부 처리

[서버 2대일 때] (자동 리밸런싱!)
Partition 0,1,2 → 서버1(app-1)의 Consumer
Partition 3,4,5 → 서버2(app-2)의 Consumer

같은 Consumer Group 내에서 Kafka가 자동으로 파티션을 분배!
→ 코드 변경 없이 서버만 추가하면 자동 스케일아웃
→ 이게 Kafka Consumer Group의 핵심 가치
```

### 통합 테스트: ConsumerRecoveryIntegrationTest

```java
class ConsumerRecoveryIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("멱등성 심화: 동일 messageKey를 Kafka로 2회 발행 → DB에 1건만 저장")
    void duplicateMessageKey_shouldSaveOnlyOnce() {
        UUID messageKey = UUID.randomUUID();
        ChatMessageEvent event = new ChatMessageEvent(
                messageKey, room.getId(), user1.getId(), "유저1",
                "중복 테스트 메시지", MessageType.TEXT, LocalDateTime.now());

        // 동일 메시지를 2회 Kafka에 발행
        kafkaTemplate.send(KafkaConfig.MESSAGES_TOPIC, String.valueOf(room.getId()), event);
        kafkaTemplate.send(KafkaConfig.MESSAGES_TOPIC, String.valueOf(room.getId()), event);

        // Consumer가 처리할 시간 대기 후 DB 확인 (Awaitility)
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            long count = messageRepository.countByMessageKey(messageKey);
            assertThat(count).isEqualTo(1);  // 2번 발행했지만 1건만 저장!
        });
    }

    @Test
    @DisplayName("Kafka → Consumer → DB 저장 E2E 검증")
    void kafkaToDbEndToEnd() {
        UUID messageKey = UUID.randomUUID();
        ChatMessageEvent event = new ChatMessageEvent(...);

        // Kafka에 직접 메시지 발행 (WebSocket 거치지 않고)
        kafkaTemplate.send(KafkaConfig.MESSAGES_TOPIC, String.valueOf(room.getId()), event);

        // DB에 메시지가 저장될 때까지 대기
        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(messageRepository.existsByMessageKey(messageKey)).isTrue();
            Message saved = messageRepository.findByMessageKey(messageKey).get();
            assertThat(saved.getContent()).isEqualTo("E2E 테스트");
            assertThat(saved.getChatRoom().getId()).isEqualTo(room.getId());
        });
    }
}
```

**Awaitility가 뭐야?**
```
Kafka Consumer는 비동기로 동작:
→ kafkaTemplate.send() 후 Consumer가 언제 처리할지 모름
→ 바로 assertThat 하면 아직 DB에 없어서 실패!

[Thread.sleep 방식]
Thread.sleep(5000);  // 5초 기다림
assertThat(count).isEqualTo(1);
문제: 5초가 충분한지 모름 (느린 환경에서 실패 가능)

[Awaitility 방식]
await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
    assertThat(count).isEqualTo(1);
});
장점: 조건 충족 시 즉시 통과 (빠른 환경: 0.5초, 느린 환경: 5초)
→ 최대 10초까지 대기, 그 전에 성공하면 바로 통과!
```

### 통합 테스트: PresenceIntegrationTest

```java
class PresenceIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("온라인 상태 설정 → 확인 → 오프라인 → 확인")
    void presenceOnlineOffline() {
        // 처음에는 오프라인
        assertThat(presenceService.isOnline(user1Id)).isFalse();

        // 온라인 설정 (Redis에 키 생성)
        presenceService.setOnline(user1Id);
        assertThat(presenceService.isOnline(user1Id)).isTrue();

        // 오프라인 설정 (Redis에서 키 삭제)
        presenceService.setOffline(user1Id);
        assertThat(presenceService.isOnline(user1Id)).isFalse();
    }

    @Test
    @DisplayName("채팅방 멤버 중 온라인 유저 조회")
    void getOnlineMembers() {
        presenceService.setOnline(user1Id);

        // user1만 온라인
        Set<Long> onlineMembers = presenceService.getOnlineMembers(roomId);
        assertThat(onlineMembers).containsExactly(user1Id);

        // user2도 온라인
        presenceService.setOnline(user2Id);
        onlineMembers = presenceService.getOnlineMembers(roomId);
        assertThat(onlineMembers).containsExactlyInAnyOrder(user1Id, user2Id);

        // REST API로도 검증
        ResponseEntity<Set<Long>> response = restTemplate.exchange(
                baseUrl + "/api/rooms/" + roomId + "/members/online",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token1)),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactlyInAnyOrder(user1Id, user2Id);
    }
}
```

### 2차 추가 파일 전체 구조

```
src/main/java/com/realtime/chat/
│
├── config/
│   ├── RateLimitInterceptor.java     ← [신규] 메시지 속도 제한 (STEP 10)
│   └── WebSocketEventListener.java   ← [신규] WebSocket 연결/해제 감지 (STEP 12)
│
├── controller/
│   └── PresenceController.java       ← [신규] 온라인 멤버 조회 API (STEP 12)
│
├── dto/
│   └── PresenceEvent.java            ← [신규] 상태 변경 이벤트 DTO (STEP 12)
│
├── service/
│   └── PresenceService.java          ← [신규] Redis 기반 상태 관리 (STEP 12)
│
├── [수정] config/KafkaConfig.java    ← DLT Recoverer 연결 (STEP 11)
├── [수정] config/RedisConfig.java    ← Presence 채널 구독 추가 (STEP 12)
├── [수정] config/SecurityConfig.java ← /actuator/health permitAll (STEP 9)
├── [수정] config/WebSocketConfig.java ← RateLimitInterceptor 등록 (STEP 10)
├── [수정] service/RedisPubSubService.java ← Presence 발행/수신 추가 (STEP 12)
├── [수정] consumer/*Consumer.java    ← 에러 로깅 강화 (STEP 11)
│
├── [수정] application.yml            ← graceful shutdown, actuator, rate-limit, 환경변수 (STEP 9,10,13)
├── [수정] docker-compose.yml         ← app-1, app-2 서비스 추가 (STEP 13)
├── [신규] Dockerfile                 ← Multi-stage 빌드 (STEP 13)
│
src/test/java/com/realtime/chat/
├── ConsumerRecoveryIntegrationTest.java ← [신규] 멱등성 + E2E 테스트 (STEP 13)
└── PresenceIntegrationTest.java         ← [신규] 온라인 상태 테스트 (STEP 13)
```

---

> 1차에서 **"메시지가 전달된다"**를 만들었고,
> 2차에서 **"안전하고, 관찰 가능하고, 확장 가능하게"**를 만들었습니다.
> 이 가이드를 읽고 코드를 다시 보면 "아, 이 코드가 이 역할을 하는 거구나"가 보일 겁니다.
> 궁금한 부분이 있다면 해당 파일을 직접 읽어보면서 흐름을 따라가보세요!
