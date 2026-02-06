추천# CLAUDE.md

## 프로젝트 개요

Kafka + WebSocket 기반 실시간 채팅 서비스 (백엔드 포트폴리오)

## 기술 스택

- Java 21, Spring Boot 3
- Spring WebSocket (STOMP)
- Apache Kafka (KRaft 모드)
- Redis (Pub/Sub + Cache)
- PostgreSQL
- Docker Compose

## 설계 문서

- 설계 문서: `docs/DESIGN.md`
- 성능 측정 결과: `docs/PERF_RESULT.md` (예정)

## 패키지 구조

```
src/main/java/com/realtime/chat/
├── config/          # WebSocket, Kafka, Redis, Security 설정
├── controller/      # REST API + WebSocket 메시지 핸들러
├── service/         # 비즈니스 로직
├── consumer/        # Kafka Consumer
├── producer/        # Kafka Producer
├── domain/          # Entity
├── repository/      # JPA Repository
├── dto/             # 요청/응답 DTO
├── event/           # Kafka 메시지 스키마
└── common/          # 공통 유틸, 예외 처리
```

## 빌드 / 실행

```bash
# 인프라 실행
docker compose up -d

# 애플리케이션 빌드 및 실행
./gradlew bootRun
```

## 코딩 컨벤션

- 언어: 한국어 주석, 영어 코드
- 네이밍: 클래스 PascalCase, 메서드/변수 camelCase, 상수 UPPER_SNAKE_CASE
- DTO: 요청 `*Request`, 응답 `*Response`
- Entity: Lombok 사용 최소화 (`@Getter`, `@NoArgsConstructor(access = PROTECTED)` 정도만)
- 테스트: `*Test` (단위), `*IntegrationTest` (통합)
