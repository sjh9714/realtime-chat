# Realtime Chat Runbook

이 문서는 로컬 검증과 면접 설명을 위한 장애 대응 절차 초안입니다. 실제 production 운영 체계나 SLO를
구현했다는 주장은 하지 않습니다.

## 1. 메시지 SEND가 실패하거나 NACK가 증가할 때

### 확인

1. `/user/queue/messages/error` payload의 reason을 확인합니다.
2. Kafka broker 연결과 `chat.messages` topic 상태를 확인합니다.
3. Redis rate-limit key가 과도하게 증가했는지 확인합니다.
4. 비멤버 room SEND 또는 malformed destination이 아닌지 확인합니다.

### 판단

- ACK는 Kafka publish accepted만 의미합니다.
- PERSISTED는 DB 저장 완료 또는 idempotent duplicate 확인입니다.
- 상대 receiver 수신 완료는 별도 receiver log로 검증해야 합니다.

## 2. Receiver delivery 누락이 의심될 때

### 확인

1. `scripts/ws-delivery-runner.mjs`로 member / send / receive / status JSONL을 생성합니다.
2. `scripts/delivery-matrix.mjs`로 expected/actual/missing/duplicate을 계산합니다.
3. accepted/persisted/statusless send를 분리합니다.
4. rate-limited send나 statusless send를 receiver delivery 분모에 섞지 않습니다.

### 주의

- 현재 runner는 `CONNECTED` 확인과 settle delay를 기본 send window barrier로 사용합니다.
- room topic receipt까지 강제로 확인하려면 `--require-room-receipts true`를 사용합니다.
- ACK/ERROR/PERSISTED user queue receipt는 기본적으로 진단값입니다. 해당 receipt까지 강제로 확인하려면
  `--require-status-receipts true`로 별도 실행합니다.
- 기존 2026-05-22 smoke summary는 barrier 도입 전/작은 도구 검증 근거이므로 public benchmark로 사용하지 않습니다.

## 3. DLT 메시지가 쌓일 때

### 확인

- `chat.messages.dlt` payload와 error를 확인합니다.
- `/actuator/prometheus`에서 `chat_messages_dlt_routed_total`과
  `chat_messages_dlt_replayed_total` 증가량을 확인합니다.
- `messageKey`가 이미 저장됐는지 확인합니다.
- replay 대상 room/member 상태가 현재도 유효한지 확인합니다.

### 조치

1. 원인을 제거한 뒤 작은 batch로 manual replay를 실행합니다.
2. `messageKey` unique guard로 중복 저장이 막히는지 확인합니다.
3. replay 후 DLT lag와 persisted ACK를 다시 확인합니다.

## 4. Presence가 실제 접속 상태와 다를 때

### 확인

- Redis `user:presence:{userId}:sessions` set과
  `user:presence:{userId}:session:{sessionId}` TTL key를 확인합니다.
- 한 사용자의 여러 session 중 일부 종료를 offline으로 해석하지 않습니다.

### 조치

- TTL 만료를 기다리거나 stale session key를 제거합니다.
- reconnect sync API로 누락 메시지를 보정합니다.

## 5. Cache hit rate가 급락할 때

### 확인

- `/actuator/prometheus`에서 `chat_rooms_cache_evictions_total` 증가량을 확인합니다.
- hot room message 저장이 room member cache를 계속 evict하는지 확인합니다.
- 관계없는 사용자 cache까지 evict하는지 확인합니다.

### 조치

- room member 범위 eviction인지 확인합니다.
- Spring cache hit/miss metric 이름과 tag를 확인하기 전까지 README에 새 수치를 추가하지 않습니다.

## 6. 서버 fan-out 지연이 증가할 때

### 확인

- `chat_messages_received_total`은 Redis room channel 수신 후 WebSocket room topic으로 브로드캐스트한
  메시지 수입니다.
- `chat_room_fanout_latency_seconds`는 Redis 수신부터 STOMP 브로드캐스트 호출까지의 서버 내부 처리
  시간입니다.

### 주의

- 이 metric은 receiver client 수신 완료가 아닙니다.
- end-to-end delivery completeness와 send-to-receive latency는 receiver matrix runner 결과와 분리해
  해석합니다.
