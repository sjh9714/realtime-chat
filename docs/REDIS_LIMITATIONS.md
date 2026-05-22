# Redis rate-limit / cache-hit limitations

이 문서는 Redis를 사용한 WebSocket SEND rate limit과 채팅방 목록 cache의 현재 한계를 정리한다. 새 성능 수치는 기록하지 않는다.

## 1. WebSocket SEND rate limit

현재 구현은 user-level fixed-window counter다.

```text
rate:ws:send:user:{userId}:{epochSecond}
```

| 항목 | 현재 정책 |
| --- | --- |
| 대상 | STOMP `SEND` frame |
| 기본 제한 | `chat.rate-limit.messages-per-second: 10` |
| 저장소 | Redis counter |
| TTL | 2초 |
| Redis 장애 | fail-closed, SEND 거부 |

### 한계

- fixed-window 방식이라 초 경계에서 burst가 생길 수 있다.
- 제한 기준이 userId라서 같은 사용자의 여러 session이 하나의 quota를 공유한다.
- room 단위 fan-out 비용과 recipient 수는 제한 기준에 포함하지 않는다.
- Redis 장애 시 abuse prevention을 우선하므로 메시지 전송 가용성이 낮아질 수 있다.

### 개선 후보

| 방식 | 장점 | 비용 / 한계 |
| --- | --- | --- |
| sliding window Lua script | 초 경계 burst 완화, Redis 원자성 유지 | 구현 복잡도 증가, Redis script 운영 고려 필요 |
| token bucket Lua script | 평균 rate와 burst capacity를 분리 가능 | bucket refill 계산과 clock 기준 관리 필요 |
| room-level limit 추가 | hot room fan-out 보호 | 정상 대화가 많은 방에서 과도 차단 가능 |

구현 전에는 기존 fixed-window와 개선안을 같은 burst 시나리오에서 비교해야 한다.

| 측정 항목 | 상태 |
| --- | --- |
| 초 경계 burst 허용량 | 추가 측정 예정 |
| fixed-window reject count | 추가 측정 예정 |
| sliding window/token bucket reject count | 추가 측정 예정 |
| 정상 traffic false positive | 추가 측정 예정 |

## 2. Cache Aside hit rate

채팅방 목록은 user별 `rooms::{userId}` cache를 사용한다. 메시지 저장 시 해당 room member cache만 evict하고, 읽음 처리는 해당 user cache만 evict한다.

### 현재 검증된 것

| 항목 | 상태 |
| --- | --- |
| N+1 제거 | 측정 완료 |
| REST 조회 중심 RPS / latency 개선 | 측정 완료 |
| 관계없는 사용자 cache 유지 | 시나리오 검증 |
| mixed traffic cache hit rate | selective eviction counter는 추가했지만, 반복 benchmark hit ratio는 추가 측정 예정 |

### 한계

- 기존 REST 부하 테스트는 조회 중심이라 실제 채팅 트래픽보다 cache hit rate가 높게 보일 수 있다.
- 메시지가 자주 저장되는 hot room의 멤버는 저장마다 cache가 evict되어 hit rate가 낮아질 수 있다.
- 방 생성/참여는 아직 영향 범위가 넓은 무효화 정책이 남아 있다.
- 현재 공개 수치에서는 N+1 제거 효과와 Redis cache 효과를 완전히 분리하지 않았다.

### 측정 계획

mixed traffic에서 아래 값을 분리해 기록한다.

| 지표 | 상태 |
| --- | --- |
| `rooms` cache hit count | Spring cache metric 이름과 tag 확인 후 기록 |
| `rooms` cache miss count | Spring cache metric 이름과 tag 확인 후 기록 |
| `rooms` cache eviction count | `chat.rooms.cache.evictions` counter로 기록 |
| cache hit ratio | 추가 측정 예정 |
| eviction count | 추가 측정 예정 |
| hot room message rate별 hit ratio 변화 | 추가 측정 예정 |
| cache hit / miss별 `GET /api/rooms` p95 | 추가 측정 예정 |

Micrometer cache metric을 사용할 경우 `cache.gets`, `cache.puts`, `cache.evictions` 계열 metric 이름과 tag를 먼저 확인한 뒤 문서에 기록한다. metric 이름을 확인하기 전에는 dashboard나 README에 수치를 추가하지 않는다.
