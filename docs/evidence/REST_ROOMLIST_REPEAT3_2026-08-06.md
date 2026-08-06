# REST 조회 부하 재측정 — 3회 반복 (2026-08-06)

`docs/PERF_RESULT.md`의 REST 수치가 historical unpinned archive로 표시되어 있어,
**현재 커밋에서 환경·명령을 고정해 다시 측정**한 기록입니다.

## 커밋과 환경

| 항목 | 값 |
| --- | --- |
| commit | `9663f58b2b7029311705ebb1eaf703d2e38f568b` |
| 측정일 | 2026-08-06 |
| 호스트 | Apple M4 · macOS 26.3.1 |
| Docker | Docker Desktop 29.4.3 · CPU 10 · MEM 8 GB |
| PostgreSQL | `postgres:16-alpine` |
| Redis | `redis:7-alpine` |
| Kafka | `apache/kafka:3.9.0` (KRaft) |
| 애플리케이션 | Spring Boot 3.4.3 · Java 21 · **단일 인스턴스** (`app-1`, host 8081) |
| 부하 도구 | k6 v1.5.0 (호스트 실행) |

## 명령

```bash
docker compose up -d --wait postgres redis kafka
docker compose up -d --build --wait app-1

k6 run --env BASE_URL=http://localhost:8081 \
  --summary-export=<out>/rest-run{N}-summary.json \
  k6/rest-api-test.js
```

## 시나리오

`k6/rest-api-test.js` — ramping-vus `0 → 50 (10s) → 200 (30s) → 0 (10s)`.
setup에서 유저 200명과 방을 생성한 뒤, 반복마다 아래 세 엔드포인트를 호출합니다.

- 채팅방 목록 조회 (`GET /api/rooms`) — N+1 최적화 대상
- 채팅방 상세 조회
- 메시지 이력 조회 (커서 페이지네이션)

즉 **목록 조회 단독 수치가 아니라 조회 계열 혼합 부하**입니다.

## 결과

| run | HTTP 요청 | RPS | med | p90 | p95 | HTTP 실패 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 129,163 | 1,806.5 | 16.26ms | 98.62ms | 129.05ms | 0.00% |
| 2 | 134,338 | 1,921.4 | 14.08ms | 96.43ms | 133.20ms | 0.00% |
| 3 | 134,755 | 1,940.2 | 14.05ms | 97.36ms | 129.09ms | 0.00% |

- 3회 합계 398,256 요청 중 **HTTP 실패 0건**
- checks 100% 통과 (목록·상세·메시지 이력 각 응답 검증)
- RPS 범위 1,806–1,940 · p95 범위 129–133ms
- k6 threshold `p(95)<500ms`, `http_req_failed<1%` 모두 통과

## Claim boundary

- **로컬 Docker 단일 머신** 측정값입니다. 운영 성능·SLO·capacity 주장이 아닙니다.
- 애플리케이션·PostgreSQL·Redis·Kafka가 **같은 머신**에서 실행되었습니다.
- JVM warmup을 별도로 두지 않았습니다.
- **개선 전(before) 수치는 함께 측정하지 않았습니다.** 저장소 히스토리에 N+1 버전이
  별도 커밋으로 남아 있지 않아 같은 환경에서 재현할 수 없었습니다.
  따라서 이 문서는 **개선율을 주장하지 않고 현재 코드의 상태만 기록**합니다.
- 단일 인스턴스 기준이며 2대 스케일아웃 수치가 아닙니다.
- WebSocket 전달 완전성(receiver matrix)은 이 측정에 포함되지 않습니다.
  `docs/LIMITATIONS.md`의 해당 항목은 그대로 유효합니다.

## 참고 — 쿼리 수

목록 조회는 방 개수와 무관하게 **3회 고정**입니다 (부하 측정이 아니라 코드로 확인되는 구조).

1. `ChatRoomRepository.findAllWithMemberInfoByUserId` — constructor expression 프로젝션 (6개 값)
2. `ChatRoomMemberRepository.findOtherMemberNicknames` — `WHERE chatRoom.id IN :roomIds`
3. `MessageRepository.findLatestByRoomIds` — `IN :roomIds` + `JOIN FETCH m.sender`

## 첨부한 summary JSON에 대해

`rest-roomlist-20260806-run{1,2,3}-summary.json`은 k6의 `--summary-export` 결과에서
`setup_data`를 제거한 것입니다. `setup_data`에는 setup 단계가 생성한 로드테스트 유저의
JWT가 들어 있어 저장소에 남기지 않았습니다(로컬 전용 계정이지만 공개 저장소에 토큰을 두지 않습니다).
측정값에 해당하는 `root_group`과 `metrics`는 그대로입니다.
