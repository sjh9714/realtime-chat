# Limitations

이 문서는 Realtime Chat이 아직 주장하지 않는 것을 분리합니다. 새 benchmark 수치는 실제 실행과 raw
artifact가 보존된 뒤에만 추가합니다.

## 현재 주장하지 않는 것

| 항목 | 현재 상태 | 다음 보강 |
| --- | --- | --- |
| send-to-receive latency | 이전 local 결과는 historical unpinned archive이며 현재 코드 evidence가 아님 | 현재 commit에서 receiver clock, clientMessageId join, 환경·명령·raw artifact를 고정해 재측정 |
| delivery completeness | 이전 receiver matrix는 historical unpinned archive이며 현재 코드의 public delivery 결과가 아님 | 현재 commit과 production에 가까운 환경에서 장시간 반복 측정 |
| room-global ordering | 구현 계약은 동일 room partition 범위이며 이전 local diagnostic은 현재 코드 evidence가 아님 | Kafka offset 기반 room sequence를 현재 commit에서 반복 검증 |
| mixed traffic p95 | 현재 room-list와 persistence pipeline 기준 공개 수치 없음 | 읽기/쓰기/receipt/cache hit ratio를 분리해 현재 commit에서 기록 |
| Redis rate-limit smoothing | fixed-window 구현 | sliding window/token bucket과 burst 비교 |
| production 운영성 | runbook 초안과 테스트 중심. Claim boundary: production/SLO 운영성을 주장하지 않음 | replay audit, dashboard, alert, SLO 검증은 별도 측정 예정 |

## 면접에서 안전하게 말할 문장

> 이 프로젝트는 다중 인스턴스 WebSocket/Kafka/Redis 채팅에서 권한, ACK/PERSISTED 의미 분리,
> idempotency, DLT replay, read receipt, presence를 검증했습니다. 다만 실제 receiver 기준 latency와
> delivery completeness는 local receiver run과 반복 benchmark를 분리해 해석합니다.
