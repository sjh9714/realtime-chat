# 전달 완전성 재측정 — 50명 · 두 노드 · 3회 반복 (2026-08-08)

직전 기록(`receiver-matrix-50users-repeat3-20260807-summary.json`, commit `18e7189`) 이후
**같은 Kafka 토픽 `chat.messages`를 읽는 컨슈머 그룹이 하나 늘었습니다** — 안내봇(`chat-bot`).
전달 경로가 바뀌었으므로 현재 커밋에서 다시 측정한 기록입니다.

## 커밋과 환경

| 항목 | 값 |
| --- | --- |
| commit | `258b837` (`feat: 방에서 활동하는 안내봇`) |
| 측정일 | 2026-08-08 |
| 호스트 | Apple M4 · macOS 26.3.1 |
| Docker | Docker Desktop 29.4.3 · CPU 10 · MEM 8 GB |
| PostgreSQL | `postgres:16-alpine` |
| Redis | `redis:7-alpine` |
| Kafka | `apache/kafka:3.9.0` (KRaft) |
| 애플리케이션 | Spring Boot 3.4.3 · Java 21 · **인스턴스 2대** (`app-1`, `app-2`) |
| 게이트웨이 | nginx `localhost:18080` — `/ws/app-1`, `/ws/app-2`로 노드 고정 |
| 러너 | `scripts/ws-delivery-runner.mjs` (Node v22.23.1, 호스트 실행) |
| 컨슈머 그룹 | `chat-persistence` · `chat-read-receipt` · **`chat-bot`** |

## 명령

```bash
docker compose -f docker-compose.demo.yml -f docker-compose.e2e.yml up -d --wait

# 3회 반복
node scripts/ws-delivery-runner.mjs \
  --base http://localhost:18080 \
  --ws ws://localhost:18080/ws/app-1,ws://localhost:18080/ws/app-2 \
  --rooms 1 --users-per-room 50 \
  --messages-per-user 50 --senders 2 --senders-per-room 2 \
  --send-interval-ms 125 \
  --subscribe-receipt-timeout-ms 5000 \
  --status-subscribe-settle-ms 250 \
  --drain-ms 5000 \
  --out-dir <out>/run{N}

node scripts/validate-delivery-evidence.mjs --artifact-dir <out>/run{N}
```

## 시나리오

한 방에 50명이 들어가고, 접속은 두 인스턴스에 나뉩니다. 그중 2명이 각각 50건을 보냅니다.

- 보낸 수 = 2 × 50 = **100건**
- 기대 수신 수 = 100 × (50 − 1) = **4,900건**

보낸 사람 자신은 분모에서 제외합니다. 같은 메시지를 두 번 받으면 중복으로 셉니다.

## 결과

| run | 기대 | 실제(고유) | 누락 | 중복 | 예상 밖 | 완전성 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 4,900 | 4,900 | 0 | 0 | 0 | 100% |
| 2 | 4,900 | 4,900 | 0 | 0 | 0 | 100% |
| 3 | 4,900 | 4,900 | 0 | 0 | 0 | 100% |

순서 위반 — 보낸 사람 기준, 그리고 DB가 발급한 `messageId` 기준 둘 다 봤습니다.

| run | sender-local 위반 | room-global 위반 | 비교 가능 건수 |
| --- | ---: | ---: | ---: |
| 1 | 0 | 0 | 4,900 |
| 2 | 0 | 0 | 4,900 |
| 3 | 0 | 0 | 4,900 |

보낸 것의 상태 — 세 run 모두 같았습니다.

| 항목 | 값 |
| --- | ---: |
| 보낸 수 | 100 |
| ACCEPTED | 100 |
| PERSISTED | 100 |
| 실패 | 0 |
| rate limit | 0 |
| 상태 없음 | 0 |

send → receive 지연(ms). **성능 수치가 아니라 시나리오가 정상적으로 흘렀는지 보는 진단값입니다.**

| run | p50 | p90 | p95 | p99 | max |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 27 | 51 | 72 | 159 | 241 |
| 2 | 28 | 44 | 54 | 99 | 120 |
| 3 | 27 | 36 | 41 | 44 | 58 |

`scripts/validate-delivery-evidence.mjs`가 3개 run 모두 통과했습니다.

## 원자료

- `receiver-matrix-50users-20260808-run1-summary.json`
- `receiver-matrix-50users-20260808-run2-summary.json`
- `receiver-matrix-50users-20260808-run3-summary.json`

## 주장하지 않는 것

로컬 Docker Compose 한 대에서 애플리케이션 2대·PostgreSQL·Redis·Kafka를 함께 돌린
시나리오 근거입니다. 러너도 같은 호스트에서 돕니다. 공개 벤치마크나 운영 성능 주장이 아닙니다.
지연 수치는 네트워크가 없는 환경의 값이라 그대로 옮겨 쓸 수 없습니다.

SUBSCRIBE receipt는 장벽으로 쓰지 않았습니다 — Spring simple broker가 receipt를 돌려주지
않기 때문입니다. 대신 `CONNECTED` 확인 뒤 250ms를 두고 보내기를 시작했습니다.
