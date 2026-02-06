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
│ Consumer Group 3: 알림        │
└──────────┬───────────────────┘
           ▼
      ┌──────────┐
      │PostgreSQL│
      └──────────┘
```

## 기술 스택

| 영역 | 기술 | 선택 이유 |
|------|------|----------|
| Runtime | Java 21, Spring Boot 3 | Virtual Thread, 검증된 에코시스템 |
| 실시간 | Spring WebSocket (STOMP) | 구독/발행 패턴 프로토콜 레벨 지원 |
| 메시지 파이프라인 | Apache Kafka | 파티션 기반 순서 보장, Consumer Group, offset 재처리 |
| 세션 공유 + 캐시 | Redis | Pub/Sub로 스케일아웃, 원자적 연산으로 동시성 처리 |
| 저장소 | PostgreSQL | 메시지 영구 저장 |
| 인프라 | Docker Compose | `docker compose up` 한 줄로 전체 환경 실행 |
| 부하 테스트 | k6 | WebSocket 프로토콜 부하 테스트 |
| 모니터링 | Prometheus, Grafana, Kafka UI | Consumer Lag, 레이턴시 p50/p95/p99 |

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
```
