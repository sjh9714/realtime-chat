# Limitations

이 문서는 Realtime Chat이 아직 주장하지 않는 것을 분리합니다. 새 benchmark 수치는 실제 실행과 raw
artifact가 보존된 뒤에만 추가합니다.

## 현재 주장하지 않는 것

| 항목 | 현재 상태 | 다음 보강 |
| --- | --- | --- |
| send-to-receive latency | local receiver snapshot은 있으나 반복 benchmark는 추가 측정 예정 | receiver clock, clientMessageId join, 실행 환경을 고정한 반복 benchmark 실행 |
| delivery completeness | 50-user repeat3와 500-user repeat3 local scenario는 있으나 public delivery benchmark는 아님 | 10 rooms / room당 50 users, 1,000 sessions 측정 |
| room-global ordering | sender-local diagnostic만 있음 | persisted message id 또는 Kafka offset 기반 room-global sequence 기록 |
| mixed traffic p95 | local smoke는 통과했지만 반복 benchmark 결과는 추가 측정 예정 | 읽기/쓰기/receipt/cache hit ratio를 분리해 기록 |
| Redis rate-limit smoothing | fixed-window 구현 | sliding window/token bucket과 burst 비교 |
| production 운영성 | runbook 초안과 테스트 중심 | replay audit, dashboard, alert, SLO 검증 |

## 면접에서 안전하게 말할 문장

> 이 프로젝트는 다중 인스턴스 WebSocket/Kafka/Redis 채팅에서 권한, ACK/PERSISTED 의미 분리,
> idempotency, DLT replay, read receipt, presence를 검증했습니다. 다만 실제 receiver 기준 latency와
> delivery completeness는 local receiver run과 반복 benchmark를 분리해 해석합니다.
