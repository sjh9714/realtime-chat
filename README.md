# Realtime Chat

Kafka + WebSocket의 기술적 깊이를 증명하는 실시간 채팅 서비스입니다.
기능보다 핵심 문제(순서 보장, 스케일아웃, 장애 복구)를 깊게 파고 정량적으로 검증합니다.

## 아키텍처

```
Client (WebSocket/STOMP)
    │
    ▼
┌─────────────────────────────┐
│  Spring Boot (Instance 1~N) │
│  WebSocket Handler + REST   │
└───────────┬─────────────────┘
            │ publish
            ▼
┌──────────────┐    ┌──────────────┐
│    Kafka     │    │    Redis     │
│  (순서 보장)  │    │ (Pub/Sub +   │
│              │    │  Cache)      │
└──────┬───────┘    └──────────────┘
       │ consume
       ▼
┌──────────────────────────────┐
│ Consumer Group 1: DB 저장     │
│ Consumer Group 2: 브로드캐스트 │
└──────────┬───────────────────┘
           ▼
      ┌──────────┐
      │PostgreSQL│
      └──────────┘
```

### 메시지 흐름

1. 클라이언트 → STOMP `/app/chat.send`로 메시지 전송
2. WebSocket Handler → Kafka `chat.messages` 토픽 발행 (partition key = roomId)
3. **Consumer Group 1**: DB 저장 + 멱등성 체크 (messageKey UUID) + unreadCount 증가
4. **Consumer Group 2**: Redis Pub/Sub → 모든 서버 인스턴스에 브로드캐스트 → WebSocket 클라이언트 전송

## 기술 스택

| 영역 | 기술 | 선택 이유 |
|------|------|----------|
| Runtime | Java 21, Spring Boot 3.4.3 | Virtual Thread, 검증된 에코시스템 |
| 실시간 | Spring WebSocket (STOMP) | 구독/발행 패턴 프로토콜 레벨 지원 |
| 메시지 파이프라인 | Apache Kafka (KRaft) | 파티션 기반 순서 보장, Consumer Group, offset 재처리 |
| 세션 공유 + 캐시 | Redis | Pub/Sub로 스케일아웃, 읽음 수 캐싱 |
| 저장소 | PostgreSQL 16 | 메시지 영구 저장 |
| 인프라 | Docker Compose | `docker compose up` 한 줄로 전체 환경 실행 |
| 테스트 | Testcontainers | PostgreSQL, Kafka, Redis 자동 구동 통합 테스트 |
| 부하 테스트 | k6 | WebSocket 프로토콜 부하 테스트 (예정) |
| 모니터링 | Prometheus, Grafana, Kafka UI | Consumer Lag, 레이턴시 p50/p95/p99 (예정) |

## 구현 완료 (MVP)

- [x] JWT 회원가입 / 로그인
- [x] 1:1 채팅방 생성 (중복 방지)
- [x] 그룹 채팅방 생성 / 참여
- [x] 실시간 메시지 전송 (WebSocket → Kafka → Redis Pub/Sub → WebSocket)
- [x] 메시지 이력 조회 (커서 기반 페이지네이션)
- [x] 읽음 처리 + 안읽은 메시지 수 (Redis 캐시 + DB fallback)
- [x] Kafka Consumer 멱등성 (messageKey UUID + DB UK)
- [x] Consumer 에러 핸들링 (3회 재시도 → DLT)
- [x] 통합 테스트 (Testcontainers)

## 핵심 기술 챌린지

각 챌린지를 **문제 인식 → 대안 비교 → 해결 → 실측 데이터**로 깊게 풉니다.

### 핵심 챌린지

| # | 챌린지 | 핵심 |
|---|--------|------|
| 1 | **메시지 순서 보장** | Kafka 파티셔닝 전략 비교 실험 (roomId vs userId) |
| 2 | **WebSocket 스케일아웃** | Redis Pub/Sub 서버 간 브로드캐스트, 1대 vs 2대 레이턴시 측정 |
| 3 | **읽음 처리 동시성** | 낙관적 락 vs 비관적 락 vs Redis 원자적 연산 비교 실험 |
| 4 | **Consumer 장애 복구** | manual offset commit + 멱등성(UUID) + DLT |

### 성능 최적화

| # | 챌린지 | 핵심 |
|---|--------|------|
| 5 | **DB 인덱스 최적화** | EXPLAIN ANALYZE 전/후, 100만 건 기준 실측 |
| 6 | **채팅방 목록 쿼리 최적화** | N+1 해결 + Redis 캐싱 (Cache Aside) |

### 프로덕션 품질

| # | 챌린지 | 핵심 |
|---|--------|------|
| 7 | **모니터링 체계** | Prometheus + Grafana 대시보드, k6 부하 테스트 연동 |
| 8 | **운영 안정성** | Graceful Shutdown, Rate Limiting, Health Check |

## 문서

- [설계 문서](docs/DESIGN.md) — 아키텍처, ERD, Kafka 토픽, 기술 챌린지 상세
- 성능 측정 결과 (PERF_RESULT.md) — 예정

## 실행 방법

```bash
# 인프라 실행
docker compose up -d

# 애플리케이션 빌드 및 실행
./gradlew bootRun

# 테스트
./gradlew test
```
