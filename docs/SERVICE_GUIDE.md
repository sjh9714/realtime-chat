# 채팅 보조 사례 — 저장과 재접속 복구

이 저장소는 Kafka 수락·PostgreSQL 저장·Redis 발행·WebSocket 수신의 경계를 비교하는 개인 학습 프로젝트입니다. 이번 보강은 AI 지원으로 문구·복구 기준·검증을 수정한 작업이며 운영 경험을 뜻하지 않습니다. 기존 봇과 화면은 보존했고 새 메시징 기술은 추가하지 않았습니다.

## 읽는 순서

1. `controller/ChatMessageController`: 로그인 사용자와 방 접근을 검사하고 전송 요청을 받습니다.
2. `src/main/java/com/realtime/chat/producer/ChatMessageProducer.java`: Kafka 접수 후 `ACCEPTED`를 알립니다. 이 시점은 DB 저장 전입니다.
3. `MessagePersistenceConsumer` → `MessagePersistenceService`: 중복 키를 확인하고 DB 저장을 커밋합니다. 그 뒤 Redis로 발행합니다. 동일 발신자·clientMessageId의 재전송은 같은 DB 행으로 합칩니다.
4. `web/src/stores/chat-store.ts`: 임시 메시지와 저장 응답을 합칩니다. `PERSISTED` 표시는 **서버 저장 완료**이며 상대방의 수신·읽음 확인이 아닙니다.
5. `web/src/hooks/use-chat-socket.ts` → `MessageService.syncMessages`: 이력 조회를 완료한 방별 `historyCursorByRoom`에서 누락분을 조회합니다. 실시간 메시지와 저장 ACK는 이 기준을 변경하지 않습니다.

## 왜 기준을 나누는가

이력 조회 완료 ID가 10이고 11을 놓친 뒤 12만 수신했다면, 12 이후 조회로는 11을 찾을 수 없습니다. 화면의 최대 ID는 12여도 복구 기준은 10이어야 합니다. 전체 페이지 조회가 끝난 뒤에만 기준을 12로 올립니다. 도중 실패하면 직전 완료 기준부터 재조회하고 메시지 ID로 중복을 합칩니다.

처음 방을 열 때는 최근 50건을 읽습니다. 빈 방은 완료 기준 `0`을 저장하며 이후 `/messages/sync?afterMessageId=0`은 오래된 순서부터 조회합니다. 기준 없는 기존 API 요청은 최근 묶음을 돌려주는 동작을 유지합니다. 이전의 더 오래된 전체 대화를 모두 내려받는 기능으로 확장한 것은 아닙니다.

## 보장과 한계

- `ACCEPTED`: Kafka 접수. 화면 문구는 ‘서버 접수’.
- `PERSISTED`: DB 저장 또는 같은 키의 기존 저장 확인. 상대방 수신 확인이 아님.
- Redis 발행 예외의 재시도: 로컬 실패 주입에서 확인. 발행이 성공했지만 구독자가 놓친 프레임은 이 재시도로 복구되지 않습니다.
- 재접속: 완료된 이력 기준 이후 DB에 남아 있는 메시지를 복구합니다. 실제 E2E에서 중간 프레임 하나를 누락시킨 뒤 더 큰 ID를 수신하고 재접속해 확인했습니다.
- 로컬 테스트·과거 50명 수신 관측은 모든 장애 조건의 exactly-once나 운영 전달률을 보장하지 않습니다. 같은 방의 Kafka 순차 처리 경로를 전제로 하며, 별도 쓰기 경로의 커밋 순서 역전까지 검증한 것은 아닙니다.

## 실행과 검증

Java 21·Docker·Node가 필요합니다. 로컬 전용 값이며 공개 환경에 사용하지 않습니다.

```sh
export CHAT_DB_PASSWORD=chat-local-demo
export JWT_SECRET=local-demo-signing-key-for-chat-never-use-in-production
docker compose -p chat-rebuild -f docker-compose.demo.yml up -d --build
```

화면 http://localhost:14173 · API http://localhost:18080. 두 브라우저 창에서 둘러보기로 들어가 서로 메시지를 보낼 수 있습니다. 종료는 같은 Compose 명령의 `stop`이며 볼륨을 보존합니다.

```sh
./gradlew test
cd web
npm ci
npm test
npm run build
```

E2E는 `docker-compose.demo.yml`에 `-f docker-compose.e2e.yml`을 더해 실행한 로컬 환경에서 `npm run e2e`로 확인합니다. 이 모드는 테스트용 실패 주입과 고정 노드 경로를 엽니다. 일반 데모에는 E2E 프로필을 사용하지 않습니다.

## 구현 후 공부

김영한 MVC·JPA로 Controller→저장 트랜잭션을 읽고, 딩코딩코 5·6주차의 비동기 처리·캐시 개념을 전달 경계에 연결합니다. 강의 원문 과제가 아닌 보충 과제입니다.

- 이력 10, 실시간 12, 누락 11을 직접 만들어 복구 전후 상태를 설명합니다.
- 두 번째 이력 페이지를 실패시켜 기준 ID가 움직이지 않는지 확인합니다.
- `PERSISTED`를 받은 시점에 상대방 창을 오프라인으로 두고, 저장과 수신의 차이를 설명합니다.
- 재전송에서 clientMessageId를 새로 만드는 경우와 재사용하는 경우의 DB 행 수를 비교합니다.
