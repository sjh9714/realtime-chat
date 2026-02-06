# Realtime Chat

Kafka + WebSocket 기술 딥다이브를 목표로 하는 실시간 채팅 서비스입니다.

## 기술 스택

| 영역 | 기술 |
|------|------|
| Runtime | Java 21, Spring Boot 3 |
| 실시간 | Spring WebSocket (STOMP) |
| 메시지 파이프라인 | Apache Kafka |
| 세션 공유 | Redis Pub/Sub |
| 캐시 | Redis |
| 저장소 | PostgreSQL |
| 인프라 | Docker Compose |
| 부하 테스트 | k6 (WebSocket) |
| 모니터링 | Prometheus, Grafana, Kafka UI |

## 핵심 기술 챌린지

1. **메시지 순서 보장** — Kafka 파티셔닝 전략 비교 (roomId vs userId)
2. **WebSocket 스케일아웃** — Redis Pub/Sub로 다중 인스턴스 세션 공유
3. **읽음 처리 동시성** — Redis 원자적 연산으로 unread_count 정합성 보장
4. **Consumer 장애 복구** — manual offset commit + 멱등성 + Dead Letter Topic
5. **연결 끊김 복구** — 재연결 시 미수신 메시지 보정

## 문서

- [설계 문서](docs/DESIGN.md)
