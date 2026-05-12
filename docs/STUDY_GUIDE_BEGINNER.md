# 실시간 채팅 서비스 — 왕초보 학습 가이드

> 이 문서는 **프로그래밍을 막 시작한 사람**도 이해할 수 있도록 작성했습니다.
> 모든 기술 용어를 **일상생활 비유**로 먼저 설명하고, 그 다음에 코드를 봅니다.
> 기존 `STUDY_GUIDE.md`보다 훨씬 쉽고 자세합니다.

---

## 목차

### Part 1: 이거 뭐 만드는 거야?
1. [카카오톡을 직접 만든다고?](#1-카카오톡을-직접-만든다고)
2. [이 프로젝트에서 쓰는 기술들 — 쉬운 설명](#2-이-프로젝트에서-쓰는-기술들--쉬운-설명)
3. [폴더 구조 — 회사 조직도처럼 이해하기](#3-폴더-구조--회사-조직도처럼-이해하기)

### Part 2: 1차 구현 (MVP) — 일단 동작하게 만들기
4. [STEP 1: 프로젝트 뼈대 — 집 짓기 전 설계도](#4-step-1-프로젝트-뼈대)
5. [STEP 2: 데이터베이스 — 데이터를 어디에 저장하지?](#5-step-2-데이터베이스)
6. [STEP 3: 로그인 — 너 누구야?](#6-step-3-로그인)
7. [STEP 4: 채팅방 — 대화할 공간 만들기](#7-step-4-채팅방)
8. [STEP 5: Kafka — 우체국 시스템](#8-step-5-kafka)
9. [STEP 6: WebSocket + Redis — 실시간 배달](#9-step-6-websocket--redis)
10. [STEP 7: 메시지 이력 + 읽음 표시](#10-step-7-메시지-이력--읽음-표시)
11. [STEP 8: 테스트 — 진짜 잘 되는지 확인](#11-step-8-테스트)
12. [메시지 여행기 — "안녕"이 전달되기까지](#12-메시지-여행기)

### Part 3: 2차 구현 (프로덕션 품질) — 진짜 서비스처럼 만들기
13. [STEP 9: 건강 검진 + 우아한 퇴장](#13-step-9-건강-검진--우아한-퇴장)
14. [STEP 10: 도배 방지](#14-step-10-도배-방지)
15. [STEP 11: 실패한 메시지 구조대](#15-step-11-실패한-메시지-구조대)
16. [STEP 12: 접속 중 표시](#16-step-12-접속-중-표시)
17. [STEP 13: 서버 여러 대로 늘리기](#17-step-13-서버-여러-대로-늘리기)

### Part 4: 3차 구현 (모니터링 + 성능 최적화) — 더 빠르고 똑똑하게
18. [STEP 14: 건강 상태판 만들기 (Prometheus + Grafana)](#18-step-14-건강-상태판-만들기)
19. [STEP 15~16: 느린 쿼리 고치기 + 캐시](#19-step-1516-느린-쿼리-고치기--캐시)
20. [STEP 17: DB 인덱스 — 책의 목차 만들기](#20-step-17-db-인덱스)
21. [STEP 18~19: 부하 테스트 — 진짜 빠른지 증명하기](#21-step-1819-부하-테스트)

### Part 5: 궁금할 수 있는 것들
22. [자주 묻는 질문 (진짜 쉬운 버전)](#22-자주-묻는-질문)

---

# Part 1: 이거 뭐 만드는 거야?

---

## 1. 카카오톡을 직접 만든다고?

### 한 줄 요약

> **카카오톡의 "채팅" 기능**을 백엔드(서버 쪽)에서 직접 만드는 프로젝트입니다.

### 카카오톡에서 메시지를 보내면 무슨 일이 벌어질까?

여러분이 카카오톡에서 친구에게 "안녕"이라고 보내면, 실제로는 이런 일이 벌어집니다:

```
1. 여러분 폰의 카카오톡 앱이 "안녕"을 서버에 전송
2. 서버가 "안녕"을 데이터베이스에 저장 (나중에 다시 볼 수 있도록)
3. 서버가 친구의 폰으로 "안녕"을 즉시 전달
4. 친구 폰에서 "안녕"이 화면에 나타남
```

**이 프로젝트는 2~3번, 즉 "서버가 하는 일"을 만듭니다.**

### 그런데 왜 이렇게 복잡해?

카카오톡 사용자가 1명이면 간단합니다. 하지만 **5,000만 명**이 동시에 쓴다면?

```
[간단한 버전 — 서버 1대]

사용자 A ──메시지──→ [서버 1대] ──전달──→ 사용자 B

문제없어 보이죠? 그런데...

Q: 서버 1대가 5,000만 명을 감당할 수 있을까?
A: 불가능! 서버를 여러 대로 늘려야 합니다.
```

```
[현실 버전 — 서버 여러 대]

사용자 A ─→ [서버 1] ─→ ???
사용자 B ─→ [서버 2] ─→ ???

문제: A가 서버1에, B가 서버2에 연결되어 있으면
서버1이 B에게 메시지를 보낼 수가 없음!

해결: "중간 전달자"가 필요
→ Kafka: 메시지를 안전하게 보관하는 우체국
→ Redis: 모든 서버에 소식을 전하는 방송국
→ WebSocket: 사용자에게 실시간으로 배달하는 배달원
```

이게 이 프로젝트의 전부입니다. **서버가 여러 대여도 메시지가 안전하고 빠르게 전달되도록** 만드는 것!

### 전체 그림 — 택배 배송으로 이해하기

```
[현실 세계 택배]
보내는 사람 → 편의점 접수 → 물류 센터 → 배달 기사 → 받는 사람

[우리 프로젝트]
사용자 A → Spring Boot → Kafka(물류센터) → Redis(배달기사) → 사용자 B
             서버          안전 보관         모든 서버에      실시간
           (편의점)        + 순서 보장        뿌림           수신
```

```
상세 흐름:

[사용자 A] ─── "안녕" ───→ [Spring Boot 서버]
                                    │
                              Kafka에 보관
                              (물류 센터)
                                    │
                         ┌──────────┴──────────┐
                         ▼                      ▼
                   [직원 1]                [직원 2]
                  DB에 저장              Redis로 방송
                (창고에 보관)          (모든 지점에 알림)
                                            │
                                    ┌───────┴───────┐
                                    ▼               ▼
                              [서버 1]          [서버 2]
                              방송 수신          방송 수신
                                    │               │
                              WebSocket         WebSocket
                              (배달원)           (배달원)
                                    │               │
                              [사용자 B]        [사용자 C]
                              "안녕" 수신       "안녕" 수신
```

---

## 2. 이 프로젝트에서 쓰는 기술들 — 쉬운 설명

### Spring Boot — "가게를 자동으로 차려주는 도구"

여러분이 카페를 차린다고 상상해보세요.

```
[Spring Boot 없이]
1. 건물을 직접 지음
2. 전기, 수도 직접 연결
3. 주방 설비 직접 설치
4. 메뉴판 직접 제작
5. 결제 시스템 직접 구축
→ 커피 한 잔 팔기까지 6개월

[Spring Boot 사용]
1. "카페 하고 싶어요" 한마디
2. 건물, 전기, 수도, 주방, 메뉴판 전부 자동 세팅
→ 바로 커피 만들기 시작!
```

```java
// 이 코드 3줄이면 웹 서버가 완성됨
@RestController
public class HelloController {
    @GetMapping("/hello")
    public String hello() { return "안녕하세요!"; }
}
// 브라우저에서 http://localhost:8080/hello 접속하면 "안녕하세요!" 표시
```

### JPA — "통역사"

여러분은 Java로 말하고, 데이터베이스(DB)는 SQL이라는 언어를 씁니다.
JPA는 Java와 DB 사이의 **통역사**입니다.

```
[JPA 없이 — 직접 SQL 작성]
String sql = "INSERT INTO users (email, password, nickname) VALUES ('a@b.com', '1234', '홍길동')";
connection.prepareStatement(sql).executeUpdate();

→ SQL 문법을 외워야 하고, 오타 나면 에러

[JPA 사용 — Java 코드로 DB 조작]
User user = new User("a@b.com", "1234", "홍길동");
userRepository.save(user);   // ← 이 한 줄이 위의 SQL을 대신함!

→ Java 코드만 알면 됨, SQL은 JPA가 자동 생성
```

```
비유: 해외 식당

JPA 없이: 메뉴판이 프랑스어 → 프랑스어를 배워야 주문 가능
JPA 사용: 한국어로 "스테이크 주세요" → 통역사가 프랑스어로 전달
```

### JWT — "놀이공원 손목 밴드"

놀이공원에 가면 입장할 때 손목 밴드를 받죠? JWT가 바로 그겁니다.

```
[놀이공원]
1. 입구에서 신분증 확인 (로그인)
2. 손목 밴드 발급 (JWT 토큰)
3. 이후 모든 놀이기구에서 밴드만 보여주면 됨 (매번 신분증 안 꺼내도 됨)
4. 밴드에 적힌 정보: "김철수, VIP, 오늘까지 유효"

[우리 서비스]
1. 이메일 + 비밀번호로 로그인
2. JWT 토큰 발급: "eyJhbGciOiJIUzI1NiJ9.eyJ..."
3. 이후 모든 API 호출 시 토큰만 전송 (매번 로그인 안 해도 됨)
4. 토큰에 담긴 정보: "userId=1, email=a@b.com, 내일까지 유효"
```

```
JWT 토큰 구조 (실제로는 암호화되어 읽을 수 없음):

eyJhbGciOiJIUzI1NiJ9        ← 봉투 (어떤 방식으로 서명했는지)
.eyJzdWIiOiIxIiwiZW1haWw...  ← 편지 내용 (userId, email, 만료 시간)
.SflKxwRJSMeKKF2QT4fwpM...  ← 봉인 도장 (위조 방지 서명)

누가 편지 내용을 바꾸면? → 봉인 도장이 안 맞음 → "위조!" 판별 가능
```

### Kafka — "초대형 우체국"

```
[일반 우체국]
편지를 보내면:
1. 우체통에 넣음
2. 우체국이 분류
3. 배달원이 배달
4. 받는 사람이 없으면? 우체국이 보관했다가 나중에 배달

[Kafka도 똑같음]
메시지를 보내면:
1. Producer(발송인)가 Kafka에 전송
2. Kafka가 Topic(분류함)에 저장
3. Consumer(수신인)가 가져감
4. Consumer가 바쁘면? Kafka가 보관했다가 나중에 전달
```

**Kafka를 더 자세히 뜯어보면:**

```
📮 Topic(토픽) = 우편함의 종류
   ├── chat.messages      = "채팅 메시지" 우편함
   └── chat.read-receipts = "읽음 확인" 우편함

📦 Partition(파티션) = 우편함 안의 칸
   chat.messages 토픽 (6칸짜리 우편함):
   ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐ ┌─────┐
   │칸 0 │ │칸 1 │ │칸 2 │ │칸 3 │ │칸 4 │ │칸 5 │
   │방1  │ │방2  │ │방3  │ │방1  │ │방2  │ │방3  │
   └─────┘ └─────┘ └─────┘ └─────┘ └─────┘ └─────┘

   왜 칸을 나누나? → 같은 방의 메시지는 같은 칸에!
   → 칸 안에서는 순서가 보장됨!
   → "안녕" 다음에 "뭐해?"가 항상 순서대로 저장

📋 Offset(오프셋) = 몇 번째 편지인지
   칸 0: [0번 편지] [1번 편지] [2번 편지] [3번 편지] ...
   → Consumer가 "나는 2번까지 읽었어" 라고 기록
   → 다음에는 3번부터 읽음

👥 Consumer Group = 같은 편지를 다른 용도로 쓰는 팀
   팀 A (chat-persistence): 편지를 복사해서 창고(DB)에 보관
   팀 B (chat-broadcast):   편지를 읽고 모든 사람에게 방송
   → 같은 편지를 2팀이 각각 처리!
```

### WebSocket — "전화 통화"

```
[HTTP = 편지]
클라이언트: "새 메시지 있어?" → 서버: "없어"     (편지 보내고 답장 받고)
클라이언트: "새 메시지 있어?" → 서버: "없어"     (또 편지 보내고 답장 받고)
클라이언트: "새 메시지 있어?" → 서버: "있어! 여기" (또...)
→ 매번 물어봐야 함 (비효율적!)

[WebSocket = 전화 통화]
클라이언트 ←──전화 연결──→ 서버  (한 번 연결하면 계속 통화 중)
서버: "새 메시지 왔어!" (서버가 먼저 말할 수 있음)
서버: "또 새 메시지!" (계속 통화 중이니까 바로 전달)
→ 물어볼 필요 없이 즉시 전달! (실시간!)
```

**STOMP = 전화 통화 매너**
```
전화 통화할 때도 규칙이 있죠?
"여보세요" → "네, 말씀하세요" → "주문할게요" → "네, 접수했습니다"

STOMP는 WebSocket 위에서 쓰는 규칙(프로토콜):
CONNECT:    "여보세요, 저 김철수인데요" (JWT 토큰으로 신분 확인)
SUBSCRIBE:  "1번 방 소식 듣고 싶어요" (구독)
SEND:       "1번 방에 '안녕' 보내주세요" (메시지 전송)
MESSAGE:    "1번 방에 새 메시지 왔어요!" (서버가 알려줌)
```

### Redis — "화이트보드 + 방송 스피커"

```
[화이트보드 역할 = 캐시]
질문: "1번 방에서 내가 안 읽은 메시지가 몇 개야?"
DB에서 찾기: 100만 건 메시지를 뒤져서 계산 → 3초 걸림
화이트보드: "3개"라고 적어둠 → 바로 답변 → 0.001초!

→ 자주 물어보는 정보를 화이트보드(Redis)에 적어두면 빠름!
→ DB는 "원본 자료실", Redis는 "빠른 메모장"

[방송 스피커 역할 = Pub/Sub]
서버가 2대인데, 메시지를 모든 서버에 알려야 함:

서버1: "1번 방에 새 메시지!" → 스피커(Redis)로 방송
서버2: 스피커 소리 들음 → "오! 내 쪽 사용자에게 전달해야겠다"

→ 서버가 몇 대든 Redis 스피커를 통해 전부 들을 수 있음!
```

### Docker Compose — "가전제품 패키지"

```
이 프로젝트를 실행하려면 이것들이 필요:
1. PostgreSQL (데이터베이스)
2. Redis (캐시 + 방송)
3. Kafka (메시지 큐)

[Docker 없이]
→ PostgreSQL 홈페이지 가서 다운로드, 설치, 계정 생성, 테이블 만들기...
→ Redis 홈페이지 가서 다운로드, 설치...
→ Kafka 홈페이지 가서 다운로드, 설치, 설정...
→ 반나절 걸림

[Docker Compose 사용]
→ docker compose up -d (이 한 줄이면 끝!)
→ 3개 전부 자동으로 다운로드 + 설치 + 실행 + 설정
→ 30초 걸림
```

### Testcontainers — "일회용 연습 환경"

```
테스트할 때 문제:
→ 진짜 DB에 테스트 데이터를 넣으면? 진짜 데이터가 오염됨!
→ 테스트용 DB를 따로 설치? 귀찮음...

Testcontainers의 해결법:
→ 테스트 시작할 때: Docker로 임시 DB, Kafka, Redis를 자동 생성
→ 테스트할 때: 이 임시 환경에서 마음껏 테스트
→ 테스트 끝나면: 임시 환경 자동 삭제

비유: "연습장"
→ 시험지에 바로 쓰면 지울 수 없음 (진짜 DB)
→ 연습장에 마음껏 풀고 버림 (Testcontainers)
```

---

## 3. 폴더 구조 — 회사 조직도처럼 이해하기

이 프로젝트를 **회사**라고 생각해보세요:

```
src/main/java/com/realtime/chat/        ← 우리 회사

├── 📋 config/          = 경영지원팀
│   │                     "회사 전체 규칙을 정하는 팀"
│   ├── SecurityConfig         → 보안 정책 (누가 출입 가능한지)
│   ├── KafkaConfig            → 우체국 계약 (어떤 우편함 쓸지)
│   ├── WebSocketConfig        → 전화 시스템 설정
│   ├── WebSocketAuthInterceptor → 전화 받을 때 신분 확인
│   ├── RateLimitInterceptor   → 도배 방지 규칙 (2차)
│   ├── WebSocketEventListener → 전화 연결/끊김 감지 (2차)
│   └── RedisConfig            → 방송 시스템 설정
│
├── 🚪 controller/     = 안내 데스크
│   │                     "고객 요청을 받아서 담당자에게 전달하는 팀"
│   ├── AuthController         → "로그인/회원가입이요" → AuthService로 연결
│   ├── ChatRoomController     → "채팅방 만들어주세요" → ChatRoomService로 연결
│   ├── MessageController      → "이전 대화 보여주세요" → MessageService로 연결
│   ├── ChatMessageController  → "이 메시지 보내주세요" → Kafka로 전달
│   └── PresenceController     → "누가 접속중이에요?" → PresenceService로 연결 (2차)
│
├── ⚙️ service/         = 실무팀
│   │                     "실제로 일하는 팀"
│   ├── AuthService            → 회원가입, 로그인 처리
│   ├── ChatRoomService        → 채팅방 만들기, 목록 조회
│   ├── MessageService         → 메시지 이력 조회
│   ├── ReadReceiptService     → 읽음 처리
│   ├── RedisPubSubService     → 방송 송출/수신
│   └── PresenceService        → 접속 상태 관리 (2차)
│
├── 📨 consumer/        = 택배 수령팀
│   │                     "Kafka에서 메시지를 받아 처리하는 팀"
│   ├── MessagePersistenceConsumer → 택배 받으면 창고(DB)에 보관
│   ├── MessageBroadcastConsumer   → 택배 받으면 방송(Redis)으로 알림
│   └── ReadReceiptConsumer        → "읽음 확인" 택배 받아서 처리
│
├── 📤 producer/        = 택배 발송팀
│   └── ChatMessageProducer    → 메시지를 Kafka 우체국에 접수
│
├── 🏛️ domain/          = 데이터 설계도
│   │                     "DB 테이블이 어떻게 생겼는지 정의"
│   ├── User                   → 사용자 (이메일, 비밀번호, 닉네임)
│   ├── ChatRoom               → 채팅방 (이름, 타입)
│   ├── ChatRoomMember         → 채팅방 멤버 (누가 어느 방에 있는지)
│   └── Message                → 메시지 (내용, 보낸 사람, 시간)
│
├── 📑 dto/             = 서류 양식
│   │                     "외부와 주고받는 데이터 형식"
│   ├── SignupRequest          → 회원가입 신청서 {email, password, nickname}
│   ├── LoginRequest           → 로그인 신청서 {email, password}
│   ├── AuthResponse           → 로그인 결과서 {token, userId, ...}
│   ├── SendMessageRequest     → 메시지 전송 양식 {roomId, content}
│   └── PresenceEvent          → 접속 상태 알림 {userId, status} (2차)
│
├── 📦 event/           = 택배 포장 규격
│   │                     "Kafka로 보내는 메시지 형식"
│   ├── ChatMessageEvent       → 채팅 메시지 택배 포장
│   └── ReadReceiptEvent       → 읽음 확인 택배 포장
│
├── 💾 repository/      = 창고 관리팀
│   │                     "DB에서 데이터를 꺼내고 넣는 팀"
│   ├── UserRepository
│   ├── ChatRoomRepository
│   ├── ChatRoomMemberRepository
│   └── MessageRepository
│
└── 🔧 common/          = 공용 도구함
    │                     "여러 팀에서 공통으로 쓰는 도구"
    ├── JwtTokenProvider       → JWT 토큰 제조기
    ├── JwtAuthenticationFilter → 모든 요청의 토큰 검사관
    ├── GlobalExceptionHandler → 에러 발생 시 깔끔한 답변 생성
    └── BusinessException      → "이메일 중복!" 같은 업무 에러
```

**DTO vs Entity — 왜 따로 만들어?**

```
비유: 이력서 vs 주민등록등본

[Entity = 주민등록등본]
이름, 주민번호, 주소, 가족관계, 범죄이력...
→ 모든 개인정보가 다 있음
→ 함부로 보여주면 안 됨!

[DTO = 이력서]
이름, 이메일, 자기소개
→ 보여줘도 되는 정보만 골라서 넣음

코드로 보면:
User Entity:  { id, email, PASSWORD, nickname, status, lastSeenAt, createdAt }
                              ↑ 비밀번호! 절대 외부에 보내면 안 됨!

AuthResponse DTO: { token, userId, email, nickname }
                    → 필요한 것만 골라서 응답
```

---

# Part 2: 1차 구현 (MVP) — 일단 동작하게 만들기

> MVP = Minimum Viable Product (최소 동작 제품)
> "모든 기능을 다 만들기 전에, 핵심 기능만 먼저 만들어서 동작하는지 확인하자!"

---

## 4. STEP 1: 프로젝트 뼈대

### build.gradle.kts — 쇼핑 목록

카페를 차릴 때 뭐가 필요할까요? 커피머신, 컵, 원두, 냉장고...
프로젝트도 마찬가지로 "필요한 도구 목록"이 있습니다.

```kotlin
dependencies {
    // 카페 기본 장비
    implementation("spring-boot-starter-web")        // 웹 서버 (카운터)
    implementation("spring-boot-starter-websocket")  // 실시간 통신 (인터폰)
    implementation("spring-boot-starter-data-jpa")   // DB 연결 (금고)
    implementation("spring-boot-starter-data-redis")  // 빠른 메모장 (화이트보드)
    implementation("spring-boot-starter-security")   // 보안 (자물쇠)
    implementation("spring-boot-starter-validation") // 입력 검사 (주문서 확인)
    implementation("spring-boot-starter-actuator")   // 건강 검진 (온도계) ← 2차

    // 특수 장비
    implementation("spring-kafka")                   // 우체국 연결 (Kafka)
    implementation("io.jsonwebtoken:jjwt-api")       // 손목밴드 제조기 (JWT)
    runtimeOnly("org.postgresql:postgresql")         // DB 드라이버 (금고 열쇠)
}
```

### docker-compose.yml — "한 번에 다 켜기"

```yaml
services:
  postgres:          # 금고 (데이터 영구 저장)
    image: postgres:16-alpine
    ports: ["5432:5432"]
    # → localhost:5432에서 접속 가능

  redis:             # 화이트보드 + 스피커 (빠른 저장 + 방송)
    image: redis:7-alpine
    ports: ["6379:6379"]

  kafka:             # 우체국 (메시지 순서 보장)
    image: apache/kafka:3.9.0
    ports: ["29092:29092"]
    # KRaft 모드: 예전에는 Kafka + Zookeeper 2개를 켜야 했는데
    # 이제 Kafka 혼자서도 됨 (더 간단!)

  kafka-ui:          # 우체국 현황판 (웹 브라우저로 확인)
    ports: ["8090:8080"]
    # → http://localhost:8090 에서 Kafka 토픽/메시지 확인 가능
```

### application.yml — "앱 설정서"

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/chat   # DB 주소 (금고 위치)
    username: chat                                # DB 계정
    password: chat1234                            # DB 비밀번호

  jpa:
    hibernate:
      ddl-auto: validate  # "테이블이 잘 만들어져 있는지 확인만 해줘"
                           # (직접 만들거나 바꾸지 마!)
    open-in-view: false    # 성능 최적화 (나중에 설명)

  kafka:
    bootstrap-servers: localhost:29092   # 우체국 주소
    consumer:
      enable-auto-commit: false         # "내가 직접 '읽었어'라고 말할게"
                                        # (자동으로 하면 위험!)

jwt:
  secret: realtime-chat-jwt-...  # 손목밴드 만드는 비밀 도장
  expiration: 86400000            # 24시간 = 86,400,000 밀리초
```

**`enable-auto-commit: false`가 왜 중요해?**

```
비유: 택배 수령 확인

[자동 확인 (auto-commit = true)]
1. 택배가 도착
2. "수령 완료!" 자동으로 사인 ← 여기!
3. 택배 뜯어보는데... 물건이 깨져있음!
4. "수령 완료"라고 이미 사인했으니 교환 불가!
→ 문제: 물건을 확인하기도 전에 "받았다"고 해버림

[수동 확인 (auto-commit = false) — 우리 선택]
1. 택배가 도착
2. 택배 뜯어서 물건 확인
3. 물건 멀쩡함! → "수령 완료!" 사인
4. 만약 물건이 깨져있었다면? → 사인 안 함 → 다시 배달해줘!
→ 안전: 처리가 완료된 후에만 "받았다"고 함
```

---

## 5. STEP 2: 데이터베이스

### 4개의 테이블 — 엑셀 시트라고 생각하세요

```
📊 users 시트 (사용자)
┌────┬──────────────┬───────────┬────────┬──────────┐
│ id │    email     │ password  │nickname│  status  │
├────┼──────────────┼───────────┼────────┼──────────┤
│  1 │ a@test.com   │ $2a$10$..│ 철수   │ ONLINE   │
│  2 │ b@test.com   │ $2a$10$..│ 영희   │ OFFLINE  │
└────┴──────────────┴───────────┴────────┴──────────┘

📊 chat_rooms 시트 (채팅방)
┌────┬──────────┬────────┬────────────┐
│ id │   name   │  type  │ created_by │
├────┼──────────┼────────┼────────────┤
│  1 │  null    │ DIRECT │     1      │  ← 1:1방은 이름 없음
│  2 │ 스터디방 │ GROUP  │     1      │
└────┴──────────┴────────┴────────────┘

📊 chat_room_members 시트 (누가 어느 방에 있나)
┌────┬─────────┬─────────┬──────────────┬──────────────┐
│ id │ room_id │ user_id │unread_count  │last_read_msg │
├────┼─────────┼─────────┼──────────────┼──────────────┤
│  1 │    1    │    1    │      0       │     50       │
│  2 │    1    │    2    │      3       │     47       │ ← 영희는 3개 안 읽음
│  3 │    2    │    1    │      0       │     30       │
└────┴─────────┴─────────┴──────────────┴──────────────┘

📊 messages 시트 (메시지)
┌────┬─────────────────────────────────┬─────────┬──────────┬─────────┐
│ id │          message_key            │ room_id │sender_id │ content │
├────┼─────────────────────────────────┼─────────┼──────────┼─────────┤
│ 47 │ 550e8400-e29b-41d4-a716-446655 │    1    │    1     │ 안녕    │
│ 48 │ 6ba7b810-9dad-11d1-80b4-00c04f │    1    │    2     │ 반가워  │
│ 50 │ f47ac10b-58cc-4372-a567-0e02b2 │    1    │    1     │ 뭐해?  │
└────┴─────────────────────────────────┴─────────┴──────────┴─────────┘
      ↑ 이게 뭐야? → "멱등성 키" — 아래에서 자세히 설명!
```

### Entity — Java 클래스 = DB 테이블

```java
@Entity                          // "이 클래스는 DB 테이블이야!"
@Table(name = "users")           // "테이블 이름은 users"
public class User {

    @Id                          // "이 필드가 고유 번호(Primary Key)"
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    // ↑ "번호는 DB가 자동으로 1, 2, 3... 매겨줘"
    private Long id;

    @Column(nullable = false, unique = true)
    // ↑ nullable = false: 빈칸 불가!
    //   unique = true:    같은 이메일 중복 불가!
    private String email;
}
```

### 멱등성(Idempotency) — "같은 택배를 두 번 받지 않는 방법"

이 개념이 이 프로젝트에서 **정말 중요**합니다!

```
[문제 상황 — 같은 메시지가 2번 저장됨]

1. 사용자가 "안녕" 전송
2. Kafka → Consumer가 "안녕"을 DB에 저장 ✓
3. Consumer가 Kafka에 "처리 완료!" 보내려는 순간... 서버 다운!
4. 서버 재시작 → Kafka: "아까 그 메시지 처리 안 됐네? 다시 보내줄게"
5. Consumer가 "안녕"을 또 DB에 저장 ← 같은 메시지가 2개!

[해결 — messageKey (UUID)]

각 메시지에 세상에서 유일한 ID를 부여:
"안녕" → messageKey: "550e8400-e29b-41d4-a716-446655440000"

Consumer가 받을 때:
1. "550e8400... 이미 있어?" → DB 확인
2. 없으면 → 저장
3. 있으면 → "아, 이미 저장한 거구나" → 스킵!

비유: 택배 송장 번호
같은 송장 번호의 택배가 2번 오면?
"아, 이거 아까 받은 거잖아" → 반송!
```

### Repository — "DB에 뭐 좀 해줘" 요청서

```java
public interface MessageRepository extends JpaRepository<Message, Long> {

    // 메서드 이름만 쓰면 JPA가 SQL을 자동으로 만들어줌!

    boolean existsByMessageKey(UUID messageKey);
    // → "이 messageKey가 DB에 있어?" (멱등성 체크용)
    // → SQL: SELECT EXISTS(SELECT 1 FROM messages WHERE message_key = ?)

    long countByMessageKey(UUID messageKey);
    // → "이 messageKey가 몇 개야?" (테스트용)
    // → SQL: SELECT COUNT(*) FROM messages WHERE message_key = ?
}
```

```
JPA가 메서드 이름을 해석하는 방법:

findByEmail("a@test.com")
find + By + Email
"찾아" + "~로" + "이메일"
→ SELECT * FROM users WHERE email = 'a@test.com'

existsByMessageKey(uuid)
exists + By + MessageKey
"존재해?" + "~로" + "메시지키"
→ SELECT EXISTS(SELECT 1 FROM messages WHERE message_key = uuid)

영어 문장 읽듯이 메서드 이름을 쓰면 JPA가 SQL을 만들어줌!
```

---

## 6. STEP 3: 로그인

### 전체 흐름 — 놀이공원 입장

```
[1단계: 회원가입 = 연간 회원 등록]

POST /api/auth/signup
요청: { "email": "a@b.com", "password": "1234", "nickname": "철수" }

서버가 하는 일:
1. "a@b.com 이미 등록되어 있나?" → DB 확인
2. "1234"를 암호화 → "$2a$10$N9qo8uLO..." (원래 비밀번호를 알 수 없게!)
3. DB에 저장: { email: "a@b.com", password: "$2a$10$...", nickname: "철수" }
4. JWT 토큰(손목밴드) 발급
5. 응답: { "token": "eyJ...", "userId": 1, "email": "a@b.com", "nickname": "철수" }
```

```
[2단계: 로그인 = 연간 회원 재방문]

POST /api/auth/login
요청: { "email": "a@b.com", "password": "1234" }

서버가 하는 일:
1. "a@b.com" → DB에서 찾기
2. 입력한 "1234"와 DB의 "$2a$10$..." 비교 → BCrypt가 알아서 판별
3. 일치! → JWT 토큰 발급
4. 응답: { "token": "eyJ...", ... }
```

```
[3단계: 이후 모든 요청 = 놀이기구 탈 때마다 밴드 확인]

GET /api/rooms
Headers: Authorization: Bearer eyJ...
                                 ↑ 이 토큰을 매번 같이 보냄

서버가 하는 일:
1. JwtAuthenticationFilter가 자동으로 토큰 확인
2. 토큰 → "userId=1, 유효기간 OK" → 통과!
3. Controller에서 @AuthenticationPrincipal Long userId = 1 자동 주입
4. "1번 유저의 채팅방 목록" 조회해서 응답
```

### 비밀번호 암호화 — 왜 그냥 저장하면 안 돼?

```
[비밀번호를 그대로 저장하면]
DB: { email: "a@b.com", password: "1234" }
→ 해커가 DB를 뚫으면? 모든 사용자의 비밀번호를 알게 됨!
→ 같은 비밀번호를 다른 사이트에서도 쓰는 사람이 많음 → 연쇄 피해

[BCrypt로 암호화하면]
DB: { email: "a@b.com", password: "$2a$10$N9qo8uLOickgx2ZMRZoMye..." }
→ 해커가 DB를 뚫어도 원래 비밀번호를 알 수 없음!
→ "$2a$10$..."에서 "1234"를 역산하는 건 수학적으로 불가능

"1234" → BCrypt → "$2a$10$N9qo8uLO..."  (한 방향으로만 변환 가능)
"$2a$10$N9qo8uLO..." → ??? → 원본 복원 불가능!

그러면 로그인할 때 어떻게 확인해?
→ 입력한 "1234"를 다시 BCrypt로 변환 → DB의 값과 비교
→ 같으면 "비밀번호 맞음!", 다르면 "틀림!"
```

### Spring Security 필터 체인 — "보안 검문소"

```
모든 HTTP 요청이 Controller에 도착하기 전에 여러 검문소를 거침:

HTTP 요청 도착
    │
    ▼
[검문소 1: CORS 필터]     "다른 사이트에서 온 요청인가?"
    │
    ▼
[검문소 2: CSRF 필터]     "위조된 요청인가?" (우리는 JWT라서 OFF)
    │
    ▼
[검문소 3: JWT 필터]      "토큰 있어? 유효해?" ← 우리가 만든 것!
    │                     → 토큰 유효하면: "userId=1" 기록
    │                     → 토큰 없으면: 그냥 통과 (다음에서 판단)
    ▼
[검문소 4: 인가 필터]     "이 URL에 접근할 수 있는 사람이야?"
    │                     → /api/auth/** → 아무나 OK (permitAll)
    │                     → /api/rooms/** → userId 있어야 함 (authenticated)
    │                     → userId 없이 /api/rooms 접근? → 401 Unauthorized!
    ▼
[Controller]              모든 검문소 통과! 비즈니스 로직 실행
```

---

## 7. STEP 4: 채팅방

### 1:1 채팅방 — "이미 대화한 적 있는지 확인"

카카오톡에서 친구에게 1:1 채팅을 시작하면:
- **처음이면**: 새 채팅방 생성
- **이전에 대화한 적 있으면**: 기존 채팅방 열기

```java
// "A와 B 사이에 이미 1:1 방이 있으면 그걸 반환, 없으면 새로 만들기"
public ChatRoomResponse createDirectRoom(Long userId, CreateDirectRoomRequest request) {

    // 1. 자기 자신과의 채팅? → 차단!
    if (userId.equals(request.getTargetUserId())) {
        throw new BusinessException("자기 자신과의 채팅방은 생성할 수 없습니다.");
    }

    // 2. 기존 1:1 방이 있는지 찾기
    return chatRoomRepository.findDirectRoomByUsers(DIRECT, userId, targetUserId)
            .map(ChatRoomResponse::from)     // 찾았으면 → 그대로 반환
            .orElseGet(() -> {               // 없으면 → 새로 생성
                ChatRoom room = new ChatRoom(null, DIRECT, currentUser);
                room.addMember(currentUser);   // 나를 멤버로 추가
                room.addMember(targetUser);    // 상대를 멤버로 추가
                chatRoomRepository.save(room);
                return ChatRoomResponse.from(room);
            });
}
```

### @AuthenticationPrincipal — "지금 로그인한 사람이 누구야?"

```java
@PostMapping("/direct")
public ResponseEntity<ChatRoomResponse> createDirectRoom(
        @AuthenticationPrincipal Long userId,  // ← 자동으로 현재 로그인한 유저의 ID가 들어옴!
        @RequestBody CreateDirectRoomRequest request) {
    // ...
}
```

```
어떻게 자동으로 들어오는 거야?

1. 클라이언트가 요청: "Authorization: Bearer eyJ..."
2. JwtAuthenticationFilter가 토큰에서 userId=1 추출
3. Spring Security에 "지금 1번 유저가 요청 중" 기록
4. Controller의 @AuthenticationPrincipal → "1번 유저요!" → userId = 1

→ Controller 코드에서 로그인 처리를 신경 쓸 필요 없음!
→ 매번 토큰 파싱하는 코드를 반복할 필요 없음!
```

---

## 8. STEP 5: Kafka

### Producer — "우체국에 편지 접수"

```java
public void sendMessage(ChatMessageEvent event) {
    String key = String.valueOf(event.getRoomId());  // 분류 기준 = 방 번호
    kafkaTemplate.send("chat.messages", key, event);
    //                  ↑ 우편함 이름   ↑ 분류키  ↑ 편지 내용
}
```

```
왜 key = roomId인가?

Kafka는 key를 기준으로 파티션(칸)을 정함:
→ roomId=1인 메시지 → 항상 파티션 3에 저장 (예시)
→ roomId=2인 메시지 → 항상 파티션 1에 저장

같은 파티션 안에서는 순서가 보장됨!
→ 1번 방의 "안녕" → "뭐해?" → "밥 먹자" 순서가 절대 바뀌지 않음!

만약 key가 없으면?
→ 메시지가 랜덤 파티션으로 흩어짐
→ "밥 먹자"가 "안녕"보다 먼저 전달될 수 있음!
→ 채팅에서 이러면 대화가 엉망이 됨
```

### Consumer — "우체국에서 편지 수령"

```java
// Consumer Group 1: DB에 저장하는 팀
@KafkaListener(topics = "chat.messages", containerFactory = "persistenceListenerFactory")
@Transactional
public void consume(ConsumerRecord<String, ChatMessageEvent> record, Acknowledgment ack) {
    ChatMessageEvent event = record.value();  // 편지 내용 꺼내기

    // 1. 이미 저장한 메시지인지 확인 (멱등성!)
    if (messageRepository.existsByMessageKey(event.getMessageKey())) {
        ack.acknowledge();  // "이미 처리했어, 다음 편지 줘"
        return;             // 저장하지 않고 넘어감
    }

    // 2. DB에 저장
    Message message = new Message(event.getMessageKey(), room, sender, ...);
    messageRepository.save(message);

    // 3. 같은 방의 다른 멤버들의 안읽은 수 +1
    chatRoomMemberRepository.incrementUnreadCountForOtherMembers(roomId, senderId);

    // 4. 모든 처리가 끝난 후에만! "받았어!" 확인
    ack.acknowledge();
}
```

**`@Transactional`이 왜 필요해?**

```
비유: 은행 계좌이체

A 계좌에서 100만원 출금 → B 계좌에 100만원 입금

만약 출금은 됐는데 입금 전에 시스템 에러가 나면?
→ A: -100만원, B: +0원 → 100만원이 증발!

@Transactional의 역할:
→ 출금과 입금을 "하나의 묶음"으로 처리
→ 둘 다 성공해야 최종 반영 (커밋)
→ 하나라도 실패하면 전부 취소 (롤백)

우리 코드에서:
1. 메시지 DB 저장 (INSERT INTO messages)
2. 안읽은 수 증가 (UPDATE chat_room_members)
→ 1은 성공, 2가 실패? → 1도 취소! 데이터 일관성 유지!
```

---

## 9. STEP 6: WebSocket + Redis

### 메시지 전송 — "카카오톡 보내기 버튼 누르면"

```java
// 클라이언트가 /app/chat.send로 보낸 메시지를 여기서 받음
@MessageMapping("/chat.send")
public void sendMessage(@Payload SendMessageRequest request, Principal principal) {
    Long userId = Long.parseLong(principal.getName());

    // 이 방의 멤버가 맞는지 확인
    if (!chatRoomMemberRepository.existsByChatRoomIdAndUserId(request.getRoomId(), userId)) {
        throw new BusinessException("채팅방에 참여하지 않은 사용자입니다.");
    }

    // Kafka에 메시지 접수 (여기서 바로 상대방에게 보내지 않음!)
    ChatMessageEvent event = ChatMessageEvent.of(
            request.getRoomId(), userId, sender.getNickname(),
            request.getContent(), request.getType()
    );
    chatMessageProducer.sendMessage(event);  // → Kafka 우체국으로!
}
```

```
"왜 바로 안 보내고 Kafka를 거쳐?"

[바로 보내는 방식]
사용자A → 서버 → 바로 사용자B에게 전달 + 별도로 DB 저장
문제 1: DB 저장 실패하면? 메시지가 사라짐!
문제 2: 서버 2대면? A는 서버1, B는 서버2 → 서버1이 B에게 못 보냄!

[Kafka 경유 방식 — 우리 선택]
사용자A → 서버 → Kafka → Consumer1(DB 저장) + Consumer2(전달)
장점 1: Kafka가 메시지를 안전하게 보관, 실패해도 재시도 가능
장점 2: 서버가 몇 대든 Consumer가 알아서 처리
장점 3: 저장과 전달이 독립적 → 하나가 느려도 다른 것에 영향 없음

단점: 몇 밀리초(0.00x초) 지연 → 사람은 감지 불가
```

### Redis Pub/Sub — "사내 방송"

```java
// Kafka Consumer 2가 Redis로 방송
public void publish(ChatMessageEvent event) {
    String channel = "chat:room:" + event.getRoomId();   // "chat:room:1"
    String message = objectMapper.writeValueAsString(event);  // JSON으로 변환
    redisTemplate.convertAndSend(channel, message);  // 방송!
}

// 모든 서버가 방송을 수신
public void onMessage(String message, String channel) {
    ChatMessageEvent event = objectMapper.readValue(message, ChatMessageEvent.class);
    String roomId = channel.replace("chat:room:", "");
    messagingTemplate.convertAndSend("/topic/room." + roomId, event);
    // ↑ 이 서버에서 1번 방을 구독 중인 모든 WebSocket 클라이언트에게 전달!
}
```

```
서버 2대일 때 어떻게 동작해?

[서버 1]                              [서버 2]
사용자 A, C 연결                       사용자 B, D 연결

1. 사용자 A가 1번 방에 "안녕" 전송
2. 서버1 → Kafka → Consumer2 → Redis 방송: "chat:room:1에 새 메시지!"

3. 서버1: 방송 들음 → "나한테 연결된 사용자 중 1번 방 구독자?"
   → 사용자 A, C 중 1번 방 구독자에게 전달

4. 서버2: 방송 들음 → "나한테 연결된 사용자 중 1번 방 구독자?"
   → 사용자 B, D 중 1번 방 구독자에게 전달

→ 어느 서버에 연결되어 있든 메시지를 받을 수 있음!
```

---

## 10. STEP 7: 메시지 이력 + 읽음 표시

### 커서 기반 페이지네이션 — "인스타그램 스크롤"

채팅방에 메시지가 10,000개면 한 번에 다 보여줄 수 없겠죠?
20개씩 나눠서 보여줘야 합니다.

```
[잘못된 방법 — Offset]
"20번째부터 20개 보여줘"

  메시지: [1] [2] [3] ... [20] [21] [22] ... [40] [41] ...
  1페이지: ─────────────────┘
  2페이지:                       └───────────────┘

  문제: 1페이지를 보는 동안 새 메시지 3개가 추가되면?
  [새1] [새2] [새3] [1] [2] [3] ... [20] [21] ...

  2페이지에서 "20번째부터" 하면:
  [18] [19] [20] → 1페이지에서 본 메시지가 또 나옴! (중복!)

[올바른 방법 — Cursor (우리 선택)]
"id=20보다 이전 것 20개 보여줘"

  메시지: [1] [2] [3] ... [20] [21] [22] ... [40]
  1페이지: "최신 20개" → [21]~[40]
  2페이지: "id=21보다 이전 것 20개" → [1]~[20]

  새 메시지가 추가되어도: [새1] [새2] [새3] [1] ... [40]
  "id=21보다 이전 것" → 여전히 [1]~[20] (영향 없음!)
```

```java
// "size+1개 조회" 트릭
public MessagePageResponse getMessages(Long roomId, Long cursor, int size) {
    int fetchSize = size + 1;  // 20개 요청했으면 21개 조회!

    List<Message> messages = messageRepository.findByRoomIdWithCursor(roomId, cursor, fetchSize);

    boolean hasMore = messages.size() > size;  // 21개 왔으면 → 더 있다!
    if (hasMore) {
        messages = messages.subList(0, size);  // 21개 중 20개만 반환
    }

    // 응답: { messages: [...20개...], hasMore: true, nextCursor: 21 }
}
```

```
왜 21개를 조회해서 20개만 보여주나?

"더 있는지" 알려면 추가 쿼리가 필요:
→ SELECT COUNT(*) FROM messages WHERE ... → 느릴 수 있음!

21개 트릭:
→ 21개 왔으면 "적어도 1개 더 있다!" → hasMore: true
→ 15개 왔으면 "이게 끝이다!" → hasMore: false
→ 추가 쿼리 없이 판단 가능! (빠름!)
```

### 읽음 처리 — "카카오톡의 숫자 1"

```
카카오톡에서 "1"이 뜨는 원리:

1. A가 B에게 "안녕" 전송
2. B의 채팅방에 "안읽은 메시지: 1" 표시
3. B가 채팅방 입장 → "여기까지 읽었어!" 서버에 알림
4. 서버: B의 unread_count를 0으로 업데이트
5. A에게도 "B가 읽었어!" 알림 (우리는 여기까지 구현 안 함)
```

```java
// 읽음 처리 흐름
public void processReadReceipt(ReadReceiptEvent event) {
    ChatRoomMember member = chatRoomMemberRepository
            .findByChatRoomIdAndUserId(event.getRoomId(), event.getUserId());

    // "뒤로 가기" 방지: 더 최신 메시지까지 읽은 경우만 업데이트
    if (member.getLastReadMessageId() < event.getLastReadMessageId()) {
        member.updateLastReadMessageId(event.getLastReadMessageId());

        // DB에서 실제 안읽은 수 재계산
        int unreadCount = messageRepository.countUnreadMessages(roomId, lastReadMessageId);
        member.updateUnreadCount(unreadCount);

        // Redis 캐시도 업데이트 (다음에 빠르게 조회하려고)
        redisTemplate.opsForValue().set(key, String.valueOf(unreadCount));
    }
}

// 안읽은 수 조회: Redis → DB fallback
public int getUnreadCount(Long roomId, Long userId) {
    String cached = redisTemplate.opsForValue().get(key);
    if (cached != null) return Integer.parseInt(cached);
    // ↑ Redis에 있으면 바로 반환 (0.001초)

    // Redis에 없으면 DB에서 조회 (0.01초)
    ChatRoomMember member = chatRoomMemberRepository.findByChatRoomIdAndUserId(...);
    int unreadCount = member.getUnreadCount();
    redisTemplate.opsForValue().set(key, String.valueOf(unreadCount));
    // ↑ 다음에는 Redis에서 바로 찾을 수 있도록 저장
    return unreadCount;
}
```

```
Redis 캐시 + DB fallback 패턴:

비유: 포스트잇 + 서류 캐비닛

"이 고객 전화번호 뭐였지?"
1단계: 모니터 옆 포스트잇 확인 (Redis) → 있으면 바로 답변! (빠름)
2단계: 없으면 서류 캐비닛 뒤져서 찾기 (DB) → 찾으면 포스트잇에 적어둠
→ 다음에 또 물어보면 포스트잇에서 바로!

Redis가 꺼지면? → 서류 캐비닛(DB)에 원본이 있으니까 괜찮음!
→ Redis는 "있으면 좋고, 없어도 서비스 가능"
```

---

## 11. STEP 8: 테스트

### 테스트가 왜 필요해?

```
비유: 자동차 출고 전 검사

자동차 공장에서 차를 만들었어요.
"잘 만든 것 같은데... 바로 팔자!" → 브레이크가 안 되면?!

반드시 검사해야 함:
✅ 엔진 시동이 걸리는지 (단위 테스트)
✅ 도로에서 실제로 달리는지 (통합 테스트)
✅ 브레이크가 작동하는지 (에러 케이스 테스트)

코드도 마찬가지:
✅ 회원가입이 되는지
✅ 로그인하면 토큰이 나오는지
✅ 중복 이메일은 거부되는지
✅ 토큰 없이 API 호출하면 401이 나오는지
```

### 단위 테스트 vs 통합 테스트

```
[단위 테스트 — 부품 하나만 검사]

"AuthService의 회원가입 로직만 테스트"
→ 진짜 DB 안 쓰고, 가짜(Mock) DB 사용
→ 0.1초 만에 실행
→ "이메일 중복이면 에러가 나는지?" 같은 것만 확인

비유: 자동차 엔진만 따로 꺼내서 테스트
→ 차에 안 달아도 엔진 작동 확인 가능

[통합 테스트 — 전체 조립 후 검사]

"진짜 HTTP 요청 → 진짜 DB → 진짜 Kafka → 결과 확인"
→ Testcontainers로 PostgreSQL, Kafka, Redis를 진짜로 실행
→ 30초 걸림
→ "회원가입 → 로그인 → 채팅방 생성" 전체 흐름 확인

비유: 완성된 자동차를 도로에서 시운전
→ 엔진, 바퀴, 브레이크 등 모든 부품이 함께 동작하는지 확인
```

```java
// 통합 테스트 예시 (실제 코드!)
@Test
void 회원가입_로그인_토큰인증_전체흐름() {
    // 1. 진짜 HTTP로 회원가입
    ResponseEntity<AuthResponse> signup = restTemplate.postForEntity(
            "/api/auth/signup", signupRequest, AuthResponse.class);
    assertThat(signup.getStatusCode()).isEqualTo(CREATED);  // 201이 와야 함!

    // 2. 진짜 HTTP로 로그인
    ResponseEntity<AuthResponse> login = restTemplate.postForEntity(
            "/api/auth/login", loginRequest, AuthResponse.class);
    assertThat(login.getBody().getToken()).isNotBlank();  // 토큰이 있어야 함!

    // 3. 토큰으로 보호된 API 접근
    headers.setBearerAuth(login.getBody().getToken());
    ResponseEntity<String> rooms = restTemplate.exchange(
            "/api/rooms", GET, new HttpEntity<>(headers), String.class);
    assertThat(rooms.getStatusCode()).isEqualTo(OK);  // 200이 와야 함!

    // 4. 토큰 없이 접근 → 거부되어야 함!
    ResponseEntity<String> unauthorized = restTemplate.getForEntity("/api/rooms", String.class);
    assertThat(unauthorized.getStatusCode()).isIn(UNAUTHORIZED, FORBIDDEN);  // 401 or 403!
}
```

---

## 12. 메시지 여행기

사용자 철수가 1번 채팅방에 **"안녕"**이라고 보내면, 이 메시지는 어떤 여행을 할까요?

```
🚀 "안녕"의 여정

[출발] 철수의 폰
  │
  │  STOMP 프레임: SEND /app/chat.send {"roomId":1, "content":"안녕"}
  │  헤더: Authorization: Bearer eyJ... (JWT 토큰)
  │
  ▼
[정류장 1] WebSocketAuthInterceptor
  │  "JWT 토큰 확인... userId=1, OK! 통과!"
  │
  ▼
[정류장 2] ChatMessageController
  │  "1번 방 멤버 맞는지 확인... OK!"
  │  ChatMessageEvent 생성:
  │  { messageKey: UUID, roomId: 1, senderId: 1,
  │    senderNickname: "철수", content: "안녕", type: TEXT }
  │
  ▼
[정류장 3] ChatMessageProducer
  │  kafkaTemplate.send("chat.messages", key="1", event)
  │  "Kafka 우체국에 접수 완료!"
  │  → Partition key = "1" → hash("1") % 6 = 3 → 파티션 3에 저장
  │
  ▼
[정류장 4] Kafka
  │  chat.messages 토픽, 파티션 3, offset 42에 기록
  │
  │  2개 팀이 각각 수령:
  ├──────────────────────────────────┐
  │                                  │
  ▼                                  ▼
[정류장 5-A]                    [정류장 5-B]
MessagePersistenceConsumer      MessageBroadcastConsumer
  │                                  │
  │ "messageKey 이미 있어?"           │ "Redis로 방송!"
  │ → 없음 → DB에 저장!             │ → PUBLISH "chat:room:1"
  │ → unreadCount +1                │
  │ → ack (처리 완료!)              │ → ack (처리 완료!)
  │                                  │
  │                                  ▼
  │                            [정류장 6] Redis Pub/Sub
  │                                  │
  │                            "chat:room:1" 채널에 방송!
  │                                  │
  │                            ┌─────┴─────┐
  │                            ▼           ▼
  │                       [서버 1]     [서버 2]
  │                      onMessage()  onMessage()
  │                            │           │
  │                            ▼           ▼
  │                     STOMP SEND    STOMP SEND
  │                     /topic/room.1 /topic/room.1
  │                            │           │
  │                            ▼           ▼
  │                        [영희]       [민수]
  │                       "안녕" 수신  "안녕" 수신
  │
  ▼
[도착] DB에 영구 저장 완료
  → 영희가 나중에 앱을 열면 GET /api/rooms/1/messages로 조회 가능
```

---

# Part 3: 2차 구현 (프로덕션 품질) — 진짜 서비스처럼 만들기

> 1차에서는 **"동작하는 채팅"**을 만들었습니다.
> 2차에서는 **"실제 서비스로 운영할 수 있는 채팅"**으로 업그레이드합니다.
>
> 비유: 1차 = 시험 주행 성공, 2차 = 안전벨트, 에어백, 속도 제한기, 계기판 장착

---

## 13. STEP 9: 건강 검진 + 우아한 퇴장

### Health Check — "서버야, 너 괜찮아?"

```
비유: 병원 건강 검진

사장님: "직원들 건강 검진 받아야지"
→ 혈압 체크 (DB 연결 정상?)
→ 심전도 (Kafka 연결 정상?)
→ 혈액 검사 (Redis 연결 정상?)
→ 모두 정상! → "이 직원 건강합니다" (UP)
→ 하나라도 이상! → "이 직원 쉬게 해주세요" (DOWN)
```

```
실제로 GET /actuator/health를 호출하면:

{
  "status": "UP",                    ← 전체: 건강!
  "components": {
    "db": { "status": "UP" },        ← DB: 정상
    "kafka": { "status": "UP" },     ← Kafka: 정상
    "redis": { "status": "UP" },     ← Redis: 정상
    "diskSpace": { "status": "UP" }  ← 디스크 공간: 충분
  }
}
```

**이걸 누가, 왜 확인해?**

```
[로드 밸런서가 확인]

로드 밸런서 = 교통 경찰
"서버 1, 2, 3 중에 어디로 보내지?"

매 5초마다:
→ 서버1 /actuator/health → UP → 요청 보내도 됨!
→ 서버2 /actuator/health → DOWN → 이 서버로 보내면 안 돼!
→ 서버3 /actuator/health → UP → 요청 보내도 됨!

결과: 서버2가 아프면 자동으로 서버1, 3에만 요청 분배
→ 사용자는 서버2가 아픈지도 모름!

[Kubernetes가 확인]

K8s: "서버2가 응답 없어? 자동으로 새 서버 띄워줄게!"
→ 서버가 죽으면 자동으로 새 서버 생성 (자가 치유)
```

### Graceful Shutdown — "퇴근 예절"

```
[나쁜 퇴근 — 그냥 컴퓨터 끄기]

5시 땡! → 바로 전원 OFF
→ 작성 중이던 문서: 저장 안 됨! 날아감!
→ 처리 중이던 이메일: 절반만 보내짐!
→ 데이터베이스: 트랜잭션 깨짐!

[좋은 퇴근 — Graceful Shutdown]

5시 땡! →
1. "새로운 업무는 안 받겠습니다" (새 요청 거부)
2. "지금 하고 있는 업무만 마무리할게요" (진행 중인 요청 완료 대기)
3. "Kafka에 '여기까지 처리했어' 기록합니다" (offset 커밋)
4. "DB 연결 정리합니다" (커넥션 풀 반환)
5. 깔끔하게 퇴근! (프로세스 종료)

최대 대기 시간: 30초 (이 안에 마무리 안 되면 강제 종료)
```

```yaml
# application.yml 설정
server:
  shutdown: graceful              # "우아하게 종료해줘"

spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s  # "최대 30초까지 기다려줄게"

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics  # 이 3개 API를 열어줘
  endpoint:
    health:
      show-details: always  # DB, Kafka, Redis 상태도 보여줘
```

```java
// SecurityConfig.java — Health Check는 로그인 없이 접근 가능하게
.authorizeHttpRequests(auth -> auth
        .requestMatchers("/api/auth/**").permitAll()
        .requestMatchers("/ws/**").permitAll()
        .requestMatchers("/actuator/health/**").permitAll()  // ← 추가!
        // 로드 밸런서는 JWT 토큰이 없으니까 permitAll 해줘야 함
        .anyRequest().authenticated())
```

---

## 14. STEP 10: 도배 방지

### 왜 필요해?

```
비유: 교실에서 한 학생이 1분에 100번 손을 들면?

선생님이 다른 학생의 질문을 못 받음!
→ "손 들기 1분에 10번까지만!" 규칙 필요

채팅에서:
→ 누군가 1초에 1,000개 메시지를 보내면?
→ Kafka에 메시지 폭탄 → 전체 서비스 느려짐
→ "1초에 10개까지만!" 규칙 필요 (사람은 절대 1초에 10개 못 침)
```

### 어떻게 동작해?

```
[유저별 카운터]

유저1: ████████░░  (8/10) → 아직 괜찮아, 보내도 됨
유저2: ██████████  (10/10) → 한계! 다음 메시지부터 차단!
유저3: ██░░░░░░░░  (2/10) → 여유 있음

1초가 지나면? → 모든 카운터 리셋!
유저1: ░░░░░░░░░░  (0/10) → 다시 보낼 수 있음
유저2: ░░░░░░░░░░  (0/10) → 다시 보낼 수 있음
```

```java
// RateLimitInterceptor.java — 핵심 로직

// 이 인터셉터는 모든 WebSocket SEND 메시지에 대해 실행됨
@Override
public Message<?> preSend(Message<?> message, MessageChannel channel) {

    // SEND 명령만 체크 (CONNECT, SUBSCRIBE는 제한 안 함)
    if (!StompCommand.SEND.equals(accessor.getCommand())) {
        return message;  // SEND가 아니면 그냥 통과
    }

    Long userId = Long.parseLong(user.getName());

    // 유저별 카운터 가져오기 (없으면 새로 생성)
    RateWindow window = rateLimitMap.computeIfAbsent(userId, k -> new RateWindow());

    if (!window.tryAcquire(messagesPerSecond)) {
        // 초과! → 에러 던짐 → 클라이언트에게 STOMP ERROR 전달
        throw new IllegalStateException("메시지 전송 속도 제한을 초과했습니다.");
    }

    return message;  // 제한 이내 → 통과!
}
```

```java
// RateWindow — 1초 윈도우 카운터

private static class RateWindow {
    private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger count = new AtomicInteger(0);

    boolean tryAcquire(int limit) {
        long now = System.currentTimeMillis();

        // 1초(1000ms)가 지났으면 카운터 리셋
        if (now - windowStart.get() >= 1000) {
            windowStart.set(now);  // 윈도우 시작 시각 갱신
            count.set(1);          // 카운터 1로 리셋 (현재 메시지 포함)
            return true;           // 통과!
        }

        // 1초 이내: 카운터 +1 하고, 제한 이내인지 확인
        return count.incrementAndGet() <= limit;
        // 11번째 메시지 → 11 > 10 → false → 차단!
    }
}
```

**ConcurrentHashMap과 AtomicInteger는 왜 쓰나?**

```
비유: 은행 창구

[일반 HashMap — 창구 1개]
손님 A, B, C가 동시에 줄 서 있는데 창구가 1개
→ A가 처리되는 동안 B, C는 기다려야 함
→ 실수로 A, B가 동시에 접근하면 잔고가 꼬일 수 있음!

[ConcurrentHashMap — 창구 여러 개 + 자동 안전장치]
→ 여러 손님이 동시에 처리 가능
→ 자동으로 안전하게 처리 (데이터가 꼬이지 않음)

WebSocket 메시지는 여러 스레드(직원)가 동시에 처리:
→ 일반 HashMap + int: 동시 접근 시 카운터가 꼬임!
   예: 카운터가 5인데, 2명이 동시에 +1 → 6이 되어야 하는데 6이 됨 (한 번 빠짐)
→ ConcurrentHashMap + AtomicInteger: 동시 접근해도 정확!
   예: 카운터가 5인데, 2명이 동시에 +1 → 정확히 7
```

### 인터셉터 실행 순서

```
WebSocket 메시지 도착
    │
    ▼
[1단계: WebSocketAuthInterceptor]
    "JWT 토큰 확인! 누구야?"
    → "userId=1, 김철수님이시네요"
    │
    ▼
[2단계: RateLimitInterceptor]
    "김철수님, 이번 1초에 몇 개 보냈지?"
    → "3개째? OK, 통과!"
    → "11개째? STOP! 속도 제한 초과!"
    │
    ▼ (통과한 경우만)
[3단계: @MessageMapping 핸들러]
    실제 비즈니스 로직 실행
```

---

## 15. STEP 11: 실패한 메시지 구조대

### 1차에서의 문제

```
[1차]
메시지 실패 → 3번 재시도 → 그래도 실패 → 포기... (메시지 증발!)

"어떤 메시지가 실패했는지 모름"
"왜 실패했는지 모름"
"나중에 재처리하고 싶어도 방법 없음"

[2차 — DLT(Dead Letter Topic) 도입]
메시지 실패 → 3번 재시도 → 그래도 실패 → DLT에 보관! (증거 보존!)

"어떤 메시지가 실패했는지 Kafka UI에서 확인 가능"
"왜 실패했는지 에러 로그에 topic, partition, offset 기록"
"나중에 재처리 가능"
```

**DLT = Dead Letter Topic (죽은 편지함)**

```
비유: 우체국의 반송 편지함

정상 편지:
[보낸 사람] → [우편함] → [배달원] → [받는 사람] ✓

반송 편지:
[보낸 사람] → [우편함] → [배달원] → 주소가 없음!
                                    → 1차 시도: 다시 가봄 → 실패
                                    → 2차 시도: 또 가봄 → 실패
                                    → 3차 시도: 마지막 → 실패
                                    → [반송 편지함(DLT)]에 보관
                                    → 나중에 우체국 직원이 확인

우리 시스템:
chat.messages → Consumer 처리 실패 (3회 재시도)
              → chat.messages.dlt에 원본 메시지 보관
              → Kafka UI(localhost:8090)에서 확인 가능
```

```java
// KafkaConfig.java — DLT 설정

// DLT 토픽 생성 (앱 시작 시 자동)
@Bean
public NewTopic messagesDltTopic() {
    return TopicBuilder.name("chat.messages.dlt")
            .partitions(1)    // 실패 메시지용이라 1칸이면 충분
            .build();
}

// DLT Recoverer: 3번 실패한 메시지를 DLT로 보냄
private DeadLetterPublishingRecoverer deadLetterRecoverer(KafkaTemplate<String, Object> kafkaTemplate) {
    return new DeadLetterPublishingRecoverer(kafkaTemplate,
            (record, ex) -> {
                // 실패 정보 로깅: "어떤 메시지가, 어디서, 왜 실패했는지"
                log.error("DLT 전송: topic={}, partition={}, offset={}, error={}",
                        record.topic(), record.partition(), record.offset(), ex.getMessage());
                // 원본 토픽 이름 + ".dlt" 토픽으로 전송
                return new TopicPartition(record.topic() + ".dlt", 0);
            });
}
```

### 에러 로깅 강화 — "범인 특정"

```
[1차 에러 로그]
"메시지 저장 실패: messageKey=abc-123"
→ 실패한 건 알겠는데... 어디서? 왜?

[2차 에러 로그]
"메시지 저장 실패: messageKey=abc-123, topic=chat.messages, partition=3, offset=42"
→ topic: 어느 우편함에서 왔는지
→ partition: 몇 번 칸에 있었는지
→ offset: 몇 번째 편지였는지
→ Kafka UI에서 정확히 찾아서 원인 분석 가능!
```

```
전체 실패 처리 흐름:

메시지 도착 → Consumer 처리
    │
    ├── 성공! → ack.acknowledge() → "OK, 다음 메시지!"
    │
    └── 실패! → DefaultErrorHandler가 개입
                │
                ├── 1초 후 재시도 (1회차) → 또 실패
                ├── 1초 후 재시도 (2회차) → 또 실패
                └── 1초 후 재시도 (3회차) → 또 실패
                    │
                    ▼
              DeadLetterPublishingRecoverer
              │
              ├── 원본 메시지를 chat.messages.dlt에 저장
              ├── 에러 정보 함께 기록
              └── Consumer는 다음 메시지 처리로 넘어감
                  (실패한 메시지 때문에 전체가 멈추지 않음!)
```

---

## 16. STEP 12: 접속 중 표시

### 카카오톡의 "접속 중" 기능

```
카카오톡 프로필에 "1분 전 접속" 이런 거 본 적 있죠?
우리도 비슷한 기능을 만듭니다:
→ 채팅방에서 "누가 지금 접속 중인지" 실시간으로 표시
```

### 전체 설계 — 4단계

```
[1단계: 감지]
사용자가 WebSocket 연결 → "이 사람 접속했네!"
사용자가 WebSocket 끊김 → "이 사람 나갔네!"
→ WebSocketEventListener가 감지

[2단계: 기록]
Redis에 기록: "user:presence:1" = "ONLINE" (60초 후 자동 삭제)
→ PresenceService가 담당

[3단계: 알림]
Redis Pub/Sub로 모든 서버에 방송: "1번 유저가 접속했어요!"
→ RedisPubSubService가 담당

[4단계: 전달]
STOMP로 클라이언트에게 전달: /topic/presence
→ 클라이언트 화면에 "철수님 접속 중" 표시
```

### 각 단계 자세히

**1단계: WebSocketEventListener — "문지기"**

```java
@Component
public class WebSocketEventListener {

    // 누군가 WebSocket에 연결하면 자동 호출
    @EventListener
    public void handleWebSocketConnect(SessionConnectEvent event) {
        Long userId = extractUserId(...);   // "누가 연결한 거야?" → userId=1
        presenceService.setOnline(userId);  // Redis에 "접속 중" 기록
        redisPubSubService.publishPresence(PresenceEvent.online(userId));
        //                                 → 모든 서버에 "1번 유저 접속!" 방송
    }

    // 누군가 WebSocket이 끊기면 자동 호출
    @EventListener
    public void handleWebSocketDisconnect(SessionDisconnectEvent event) {
        Long userId = extractUserId(...);
        presenceService.setOffline(userId);  // Redis에서 "접속 중" 삭제
        redisPubSubService.publishPresence(PresenceEvent.offline(userId));
        //                                 → 모든 서버에 "1번 유저 퇴장!" 방송
    }
}
```

```
@EventListener가 뭐야?

비유: 건물 출입 센서

센서를 달아놓으면:
→ 누가 들어오면 "삐~" 소리 (SessionConnectEvent)
→ 누가 나가면 "삐~" 소리 (SessionDisconnectEvent)

개발자는 "삐~ 소리 나면 이거 해줘"라고만 코드를 짜면 됨
→ 센서(Spring)가 알아서 감지하고 알려줌!
```

**2단계: PresenceService — "출석부"**

```java
@Service
public class PresenceService {

    // Redis에 저장하는 키: "user:presence:{userId}"
    // 값: "ONLINE"
    // TTL(유효기간): 60초

    public void setOnline(Long userId) {
        // Redis: SET "user:presence:1" "ONLINE" EX 60
        // → 60초 후 자동 삭제!
        redisTemplate.opsForValue().set(
            "user:presence:" + userId, "ONLINE", Duration.ofSeconds(60));
    }

    public void setOffline(Long userId) {
        // Redis: DEL "user:presence:1"
        // → 즉시 삭제
        redisTemplate.delete("user:presence:" + userId);
    }

    public boolean isOnline(Long userId) {
        // Redis: EXISTS "user:presence:1"
        // → 키가 있으면 true(접속 중), 없으면 false(오프라인)
        return Boolean.TRUE.equals(redisTemplate.hasKey("user:presence:" + userId));
    }

    public Set<Long> getOnlineMembers(Long roomId) {
        // 1. DB에서 이 방의 멤버 목록 가져오기
        // 2. 각 멤버가 온라인인지 Redis에서 확인
        // 3. 온라인인 멤버만 반환
    }
}
```

**TTL(Time To Live) 60초 — 왜?**

```
비유: 도서관 좌석 예약

"좌석에 앉으면 1시간 예약됩니다. 1시간 후에도 계시면 다시 예약해주세요."

[정상 퇴장]
학생이 짐 싸고 나감 → "좌석 비었음" 표시 → OK!

[비정상 퇴장 — 정전으로 건물 대피]
학생이 짐도 못 챙기고 나감 → "좌석 사용 중" 그대로!
→ 영원히 "사용 중"으로 표시?!

[TTL로 해결]
1시간 후 자동으로 "비었음" 표시
→ 정상 퇴장이든 비정상 퇴장이든, 최대 1시간 후에는 정리됨

우리 서비스:
서버가 갑자기 꺼지면 → SessionDisconnectEvent가 안 나옴
→ Redis의 "user:presence:1" 키가 삭제되지 않음
→ BUT! 60초 후 TTL에 의해 자동 삭제!
→ 최대 60초 후에는 올바르게 "오프라인" 표시
```

**3단계 + 4단계: 서버 간 상태 공유 + 클라이언트 전달**

```
왜 Redis Pub/Sub가 필요해?

서버가 2대인 경우:
사용자 A → [서버 1] ← 여기서 접속 감지
사용자 B → [서버 2] ← B도 "A 접속 중"을 알아야 함!

서버 1만 알면 안 되고, 서버 2도 알아야 함:
→ Redis Pub/Sub로 모든 서버에 방송!

흐름:
서버1: "A 접속!" → Redis PUBLISH "chat:presence" → 모든 서버에 전달
서버1: 방송 수신 → /topic/presence로 → 사용자 A에게 알림
서버2: 방송 수신 → /topic/presence로 → 사용자 B에게 알림

결과: A, B 모두 "A가 접속 중"이라는 것을 알게 됨!
```

### REST API — 채팅방 입장 시 초기 상태

```java
// GET /api/rooms/{roomId}/members/online
// → 채팅방 멤버 중 지금 접속 중인 사람의 ID 목록

@GetMapping("/{roomId}/members/online")
public ResponseEntity<Set<Long>> getOnlineMembers(@PathVariable Long roomId) {
    return ResponseEntity.ok(presenceService.getOnlineMembers(roomId));
}
// 응답 예시: [1, 3, 5]  ← 1번, 3번, 5번 유저가 접속 중
```

```
클라이언트 입장에서:

채팅방에 들어올 때:
1. REST API로 현재 접속 중인 멤버 목록 가져오기 (초기 상태)
2. WebSocket으로 /topic/presence 구독 (이후 변경 사항 실시간 수신)

→ 처음에는 REST API로 현재 상태를 가져오고
→ 이후에는 WebSocket으로 변경만 수신 (효율적!)
```

---

## 17. STEP 13: 서버 여러 대로 늘리기

### Dockerfile — "앱을 상자에 넣기"

Docker는 우리 앱을 **어디서든 실행할 수 있는 상자**에 담는 도구입니다.

```
비유: 이사할 때 짐 싸기

[Dockerfile 없이]
"이 앱 실행하려면 Java 21 깔고, Gradle 깔고, 소스 다운로드하고,
빌드하고... 설정 파일 수정하고..."
→ 다른 컴퓨터에서 실행하려면 이걸 또 해야 함!

[Dockerfile 사용]
"docker run 하면 끝!"
→ Java, 소스, 설정 모든 게 상자(Docker 이미지) 안에!
→ 어느 컴퓨터든 docker run만 하면 동일하게 실행
```

```dockerfile
# 우리 Dockerfile — 2단계로 나눔 (Multi-stage Build)

# 1단계: 요리 (빌드)
FROM eclipse-temurin:21-jdk AS build    # Java 21 개발 도구 (도마, 칼, 냄비...)
WORKDIR /app
COPY gradle/ gradle/
COPY gradlew build.gradle.kts settings.gradle.kts ./
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true
# ↑ 재료(의존성) 먼저 준비 — 다음에 요리할 때 이 단계는 건너뜀! (캐싱)

COPY src/ src/
RUN ./gradlew bootJar --no-daemon -x test
# ↑ 요리 완성! (JAR 파일 = 완성된 요리)

# 2단계: 서빙 (실행)
FROM eclipse-temurin:21-jre             # Java 21 실행기만 (접시, 포크)
WORKDIR /app
COPY --from=build /app/build/libs/*.jar app.jar
# ↑ 1단계에서 만든 JAR만 가져옴 (도마, 칼, 냄비는 필요 없음!)

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
# ↑ 이 명령어로 앱 실행
```

```
왜 2단계로 나누나?

[1단계 이미지 크기]
JDK(400MB) + 소스 코드(10MB) + Gradle(100MB) + JAR(50MB) = ~560MB

[2단계 이미지 크기]
JRE(200MB) + JAR(50MB) = ~250MB

→ 절반 이상 줄어듦!
→ 소스 코드가 최종 이미지에 없음 (보안!)
→ 서버에 배포할 때 다운로드 시간 절약
```

### docker-compose — "서버 2대 동시 실행"

```yaml
# docker-compose.yml에 추가된 부분

app-1:                           # 서버 1호
    build: .                     # 위의 Dockerfile로 이미지 생성
    environment:
      DB_HOST: postgres          # Docker 내부 네트워크에서 컨테이너 이름으로 접근
      REDIS_HOST: redis
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    ports:
      - "8081:8080"              # 내 컴퓨터의 8081번 → 컨테이너의 8080번
    depends_on:
      postgres:
        condition: service_healthy  # PostgreSQL이 준비될 때까지 대기!

app-2:                           # 서버 2호 (설정 동일, 포트만 다름)
    build: .
    environment:
      DB_HOST: postgres
      REDIS_HOST: redis
      KAFKA_BOOTSTRAP_SERVERS: kafka:9092
    ports:
      - "8082:8080"              # 내 컴퓨터의 8082번
    depends_on: ...
```

**환경변수 fallback — "어디서 실행되든 OK"**

```yaml
# application.yml
spring:
  datasource:
    url: jdbc:postgresql://${DB_HOST:localhost}:${DB_PORT:5432}/chat
#                          ↑ 환경변수가 있으면 그 값, 없으면 localhost

# 로컬 개발: DB_HOST 환경변수 없음 → localhost 사용
# Docker:   DB_HOST=postgres 환경변수 설정 → postgres 사용

→ 같은 코드로 로컬에서도, Docker에서도 실행 가능!
```

**depends_on + service_healthy가 왜 중요해?**

```
비유: 요리 순서

[depends_on만 사용]
"밥 짓기" 시작 → "반찬 만들기" 바로 시작
→ 밥이 아직 안 됐는데 "식사 준비 완료!"라고 할 수 있음!

[depends_on + service_healthy 사용]
"밥 짓기" 시작 → 밥솥이 "밥 다 됐어!" 알림
→ 그제서야 "반찬 만들기" 시작

Docker에서:
postgres 컨테이너 시작 → DB 초기화 중 (아직 준비 안 됨!)
healthcheck: pg_isready → "아직이요..." → "아직이요..." → "준비 됐어요!"
→ 그제서야 app-1 시작 → DB 연결 성공!
```

### Kafka Consumer Group의 마법 — 자동 일 분배

```
[서버 1대일 때]
chat.messages 토픽 (파티션 6개)
파티션 0, 1, 2, 3, 4, 5 → 전부 서버1의 Consumer가 처리

[서버 2대로 늘리면? — 자동 리밸런싱!]
파티션 0, 1, 2 → 서버1(app-1)의 Consumer
파티션 3, 4, 5 → 서버2(app-2)의 Consumer

코드를 한 줄도 안 바꿨는데 자동으로 분배됨!

[서버 3대로 늘리면?]
파티션 0, 1 → 서버1
파티션 2, 3 → 서버2
파티션 4, 5 → 서버3

→ 서버만 추가하면 자동으로 부하 분산!
→ 이게 Kafka Consumer Group의 핵심 가치!
→ 단, 파티션 수(6) 이상의 서버는 의미 없음 (노는 서버 발생)
```

### 통합 테스트

**ConsumerRecoveryIntegrationTest — "Kafka 우체국이 잘 동작하는지 확인"**

```java
@Test
@DisplayName("같은 메시지를 2번 보내도 DB에 1개만 저장되는지 확인")
void duplicateMessageKey_shouldSaveOnlyOnce() {
    UUID messageKey = UUID.randomUUID();  // 유일한 메시지 ID 생성
    ChatMessageEvent event = new ChatMessageEvent(
            messageKey, room.getId(), user1.getId(), "유저1",
            "중복 테스트", MessageType.TEXT, LocalDateTime.now());

    // 같은 메시지를 2번 Kafka에 전송!
    kafkaTemplate.send("chat.messages", String.valueOf(room.getId()), event);
    kafkaTemplate.send("chat.messages", String.valueOf(room.getId()), event);

    // 기다렸다가... DB에 1건만 저장되었는지 확인
    await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
        long count = messageRepository.countByMessageKey(messageKey);
        assertThat(count).isEqualTo(1);  // 2번 보냈지만 1건만 있어야 함!
    });
}
```

**Awaitility — "비동기 작업 기다리기"**

```
비유: 피자 주문

"피자 주세요!" 하고 바로 "피자 왔어?"라고 확인하면?
→ 아직 만들고 있는데! 당연히 안 왔지!

[Thread.sleep 방식 — 무조건 기다리기]
Thread.sleep(5000);  // 5초 기다림
"피자 왔어?" → 빠른 날은 2초면 되는데 5초나 기다림 (비효율)
             → 느린 날은 5초도 부족할 수 있음 (불안정)

[Awaitility 방식 — 될 때까지 기다리기]
await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
    assertThat(피자왔는지).isTrue();
});
// 빠른 날: 0.5초 만에 확인 → 바로 통과!
// 느린 날: 7초 후 확인 → 그때 통과!
// 10초 안에 안 오면? → 테스트 실패! (뭔가 문제 있음)

→ 빠를 때는 빠르게, 느릴 때도 안전하게!
```

**PresenceIntegrationTest — "접속 상태가 잘 동작하는지 확인"**

```java
@Test
@DisplayName("온라인으로 바꾸고 → 확인 → 오프라인으로 바꾸고 → 확인")
void presenceOnlineOffline() {
    // 처음: 오프라인이어야 함
    assertThat(presenceService.isOnline(user1Id)).isFalse();

    // 온라인으로 설정
    presenceService.setOnline(user1Id);
    assertThat(presenceService.isOnline(user1Id)).isTrue();  // 온라인이어야 함!

    // 다시 오프라인으로 설정
    presenceService.setOffline(user1Id);
    assertThat(presenceService.isOnline(user1Id)).isFalse(); // 오프라인이어야 함!
}

@Test
@DisplayName("채팅방에서 누가 접속 중인지 조회")
void getOnlineMembers() {
    presenceService.setOnline(user1Id);         // user1만 온라인

    Set<Long> online = presenceService.getOnlineMembers(roomId);
    assertThat(online).containsExactly(user1Id); // user1만 있어야 함!

    presenceService.setOnline(user2Id);         // user2도 온라인

    online = presenceService.getOnlineMembers(roomId);
    assertThat(online).containsExactlyInAnyOrder(user1Id, user2Id);
    // user1, user2 둘 다 있어야 함! (순서 상관없이)

    // REST API로도 같은 결과가 나오는지 확인
    ResponseEntity<Set<Long>> response = restTemplate.exchange(
            baseUrl + "/api/rooms/" + roomId + "/members/online",
            HttpMethod.GET,
            new HttpEntity<>(authHeaders(token1)),
            new ParameterizedTypeReference<>() {});

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody()).containsExactlyInAnyOrder(user1Id, user2Id);
}
```

---

# Part 4: 궁금할 수 있는 것들

---

---

# Part 4: 3차 구현 (모니터링 + 성능 최적화)

> 2차까지는 "동작하게" 만들었습니다. 3차에서는 "빠르게" 만듭니다.
> 자세한 수치와 분석은 [성능 최적화 기록](PERF_RESULT.md)에 있습니다.

---

## 18. STEP 14: 건강 상태판 만들기

### 한 줄 요약

> 서버가 지금 **얼마나 바쁜지, 어디가 아픈지** 실시간으로 보여주는 대시보드를 만듭니다.

### 비유: 자동차 계기판

```
자동차를 운전할 때:
→ 속도계: 지금 얼마나 빨리 가는지
→ RPM: 엔진이 얼마나 열심히 도는지
→ 연료계: 기름이 얼마나 남았는지
→ 경고등: 뭔가 문제가 있으면 빨간불

서버도 마찬가지:
→ RPS: 초당 몇 개의 요청을 처리하는지
→ 응답시간: 한 요청에 얼마나 걸리는지
→ 에러율: 실패하는 요청이 얼마나 되는지
→ Kafka Consumer Lag: 처리 못한 메시지가 쌓이고 있는지
```

### 어떻게 구현했나?

```
[서버] ──메트릭 수집──→ [Prometheus] ──시각화──→ [Grafana 대시보드]

Prometheus: 서버한테 "지금 상태 어때?" 하고 주기적으로 물어보는 역할
Grafana: Prometheus가 모은 데이터를 예쁜 그래프로 보여주는 역할

커스텀 메트릭 5개:
1. chat_messages_sent_total: 전송된 채팅 메시지 수
2. chat_messages_consumed_total: Kafka Consumer가 처리한 메시지 수
3. chat_websocket_sessions: 현재 WebSocket 연결 수
4. chat_rooms_created_total: 생성된 채팅방 수
5. chat_message_consume_duration: 메시지 처리 시간
```

---

## 19. STEP 15~16: 느린 쿼리 고치기 + 캐시

### 한 줄 요약

> 채팅방 목록을 불러올 때 **쿼리 21개를 1개로 줄이고**, 자주 보는 데이터는 **기억해뒀다가 바로 돌려줍니다**.

### 비유: 출석 확인

```
[Before — N+1 문제]
선생님: "1반 학생 명단 주세요"
교무실: (학생 명단을 줌)
선생님: "1번 학생 정보 주세요" → 교무실 다녀옴
선생님: "2번 학생 정보 주세요" → 교무실 다녀옴
선생님: "3번 학생 정보 주세요" → 교무실 다녀옴
...10번 반복...
→ 교무실을 11번 왕복! (1번 명단 + 10번 개별)

[After — JPQL 프로젝션]
선생님: "1반 학생 이름, 출석 수, 과목 수 한 번에 주세요"
교무실: (필요한 정보만 한 장에 정리해서 줌)
→ 교무실을 1번만 왕복!
```

```
[캐시 — Redis Cache Aside]
선생님이 같은 반 명단을 5분 안에 또 물어보면?

첫 번째: 교무실(DB)에 가서 가져옴 → 칠판(Redis)에 적어둠
두 번째: 칠판만 보면 됨! 교무실 안 가도 됨

5분 지나면? 칠판 지우고 다시 교무실에서 가져옴
누가 전학 오면? 칠판 지우고 다시 교무실에서 가져옴 (최신 정보 보장)
```

### 결과

```
Before: 채팅방 10개 → DB 쿼리 21개 (1 + 10 + 10)
After:  채팅방 10개 → DB 쿼리 1개 (+ 캐시 히트 시 0개)
```

---

## 20. STEP 17: DB 인덱스

### 한 줄 요약

> DB에서 데이터를 찾을 때 **처음부터 끝까지 훑지 않고 바로 찾을 수 있도록** 목차를 만듭니다.

### 비유: 도서관에서 책 찾기

```
[인덱스 없음]
"해리포터" 찾으려면 → 1층부터 책장 하나하나 다 확인
→ 책이 10만 권이면 10만 번 확인해야 할 수도...

[인덱스 있음]
도서관 검색 컴퓨터에 "해리포터" 검색
→ "3층 A-7 책장, 위에서 3번째" → 바로 찾음!
```

### 실제로 만든 인덱스

```
총 5개 인덱스 설계:

1. 메시지 조회용 (room_id + id): 채팅방의 메시지를 최신순으로 빠르게 조회
2. 멱등성 체크용 (message_key): 같은 메시지가 중복 저장되지 않도록
3. 멤버 확인용 (room_id + user_id): "이 유저가 이 방의 멤버인가?"
4. 채팅방 목록용 (user_id): "이 유저의 채팅방 목록"
5. 발신자 조회용 (sender_id): 관리/검색 기능용

모든 주요 쿼리가 인덱스를 사용하는 것을 EXPLAIN ANALYZE로 확인 완료
```

---

## 21. STEP 18~19: 부하 테스트

### 한 줄 요약

> **k6**로 가짜 유저 200명을 만들어서 서버를 때리고, 최적화 전후를 비교합니다.

### 비유: 놀이공원 스트레스 테스트

```
놀이공원 개장 전에 직원 200명이 손님 역할을 하며 테스트:
→ 매표소에 200명이 몰리면 줄이 얼마나 길어지는지?
→ 놀이기구를 한 번 타는 데 얼마나 걸리는지?
→ 직원이 2명이면 1명일 때보다 얼마나 빨라지는지?

우리 프로젝트:
→ 가짜 유저 200명이 채팅방 목록/상세/메시지 조회를 반복
→ 최적화 전(N+1)과 후(단일 쿼리+캐시)를 비교
```

### 결과 (Before vs After)

```
┌──────────────┬──────────┬──────────┬──────────┐
│   메트릭      │  Before  │  After   │  개선    │
├──────────────┼──────────┼──────────┼──────────┤
│ 초당 처리량   │  937 RPS │ 1,598 RPS│  +70%   │
│ 응답시간 중앙 │  54ms    │  16ms    │  -69%   │
│ 응답시간 p95  │  212ms   │  149ms   │  -30%   │
│ 총 처리량     │  67,417  │ 118,900  │  +76%   │
└──────────────┴──────────┴──────────┴──────────┘

WebSocket: 579 동시 세션, 2대 스케일아웃 시 1,158 세션 (2배)
```

> 자세한 분석은 [성능 최적화 기록](PERF_RESULT.md)을 참고하세요.

---

## 22. 자주 묻는 질문

### Q: 서버가 2대인데 같은 DB를 쓰면 문제 없어?

```
A: 문제없어요!

DB(PostgreSQL)는 원래 여러 프로그램이 동시에 접근하도록 설계되어 있어요.

비유: 도서관
→ 사서가 1명이어도, 학생 100명이 동시에 책을 빌리고 반납할 수 있음
→ 도서관(DB)이 알아서 순서를 관리해줌

다만, 같은 데이터를 동시에 수정하면 충돌 가능:
→ 이걸 해결하는 게 @Transactional, 낙관적 락 등
→ 우리 프로젝트에서는 incrementUnreadCount 같은 원자적 연산으로 해결
```

### Q: Redis가 죽으면 서비스도 죽어?

```
A: 아니요! 느려지지만 죽지는 않아요.

Redis가 하는 일:
1. Pub/Sub (서버 간 방송) → Redis 없으면 다른 서버로 메시지 전달 불가
2. 캐시 (안읽은 수) → Redis 없으면 매번 DB 조회 (느리지만 동작)
3. Presence (접속 상태) → Redis 없으면 접속 상태 표시 불가

핵심 데이터(메시지, 유저, 채팅방)는 전부 DB에 있으므로:
→ Redis가 죽어도 "느린 채팅"은 가능
→ 물론 Redis가 죽으면 빨리 고쳐야 함!
```

### Q: Kafka가 죽으면?

```
A: 새 메시지 전송이 안 돼요. 하지만 기존 데이터는 안전해요.

Kafka가 죽으면:
→ 새 메시지 전송: 불가 (Kafka가 중간 역할이니까)
→ 기존 메시지 조회: 가능 (DB에 이미 저장되어 있으니까)
→ 읽음 처리: 불가 (Kafka를 거쳐서 처리하니까)

결론: Kafka는 매우 중요한 인프라
→ 프로덕션에서는 Kafka 클러스터(여러 대)로 운영
→ 1대가 죽어도 다른 대가 처리
```

### Q: @Setter를 안 쓰는 이유?

```java
// @Setter를 쓰면 (나쁜 예):
user.setPassword("1234");       // 암호화 안 된 비밀번호가 그대로 저장될 수 있음!
user.setStatus(null);           // null로 설정 가능... 의도한 건가?
user.setCreatedAt(someDate);    // 생성 시각을 마음대로 바꿀 수 있음!

// @Setter 없이 의미 있는 메서드만 제공 (좋은 예):
user.updateStatus(UserStatus.ONLINE);   // "상태를 온라인으로 바꾸기"
user.updateLastSeenAt();                 // "마지막 접속 시각 갱신"
// → 어떤 변경이 가능한지 명확하고, 잘못된 변경은 불가능!

비유: 자판기 vs 공개 냉장고
@Setter: 냉장고 문을 열어둠 → 아무 음료나 넣고 빼고 가능 (위험!)
메서드: 자판기 → "콜라 버튼", "사이다 버튼"만 있음 (안전!)
```

### Q: 왜 Kafka Consumer가 2개 그룹이야? 1개로 하면 안 돼?

```
1개 Consumer로 "DB 저장 + 방송"을 같이 하면?

문제 1: DB 저장이 느려지면 방송도 느려짐
→ 1개가 모든 일을 하니까, 하나가 밀리면 전부 밀림

문제 2: 방송 에러가 나면 DB 저장도 실패?
→ 하나의 트랜잭션에서 처리하면 그럴 수 있음

2개 Consumer Group으로 분리하면:
→ DB 저장이 느려도 방송은 독립적으로 동작
→ 방송 에러가 나도 DB 저장은 정상 진행
→ 각각 독립적으로 스케일 조절 가능

비유: 회사에서 영업팀과 물류팀을 분리하는 것
→ 영업이 바빠도 물류는 자기 속도로 일함
→ 물류에 문제가 생겨도 영업은 계속 주문을 받음
```

### Q: 이 프로젝트 코드를 읽는 순서?

```
추천 순서:

1단계: "어떻게 생겼는지" 감 잡기
→ CLAUDE.md (프로젝트 개요)
→ 이 학습 가이드 (전체 그림)

2단계: "데이터가 어떻게 생겼는지" 이해
→ domain/ 폴더 (User, ChatRoom, Message 등)
→ db/migration/ 폴더 (Flyway 테이블 구조)

3단계: "API가 뭐가 있는지" 확인
→ controller/ 폴더 (어떤 요청을 받는지)
→ dto/ 폴더 (요청/응답 형식)

4단계: "비즈니스 로직" 이해
→ service/ 폴더 (실제로 무슨 일을 하는지)

5단계: "메시지 흐름" 따라가기
→ producer/ → consumer/ → RedisPubSubService
→ 이 가이드의 "메시지 여행기" 섹션 참고

6단계: "설정" 이해
→ config/ 폴더 (Kafka, Redis, WebSocket, Security 설정)

7단계: "테스트" 실행
→ test/ 폴더 (./gradlew test로 실행해보기)
```

---

> 이 가이드를 처음부터 끝까지 읽었다면, 이 프로젝트의 **모든 코드가 왜 거기에 있는지** 이해할 수 있을 겁니다.
> 이해가 안 되는 부분이 있다면 **해당 파일을 직접 열어보면서** 이 가이드의 설명과 비교해보세요!
> 코드를 읽는 가장 좋은 방법은 **실제로 실행해보면서** 동작을 확인하는 것입니다.
>
> ```bash
> docker compose up -d    # 인프라 실행
> ./gradlew bootRun       # 앱 실행
> ./gradlew test          # 테스트 실행
> ```
