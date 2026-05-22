# Interview Guide

## 30초 요약

다중 인스턴스 채팅에서 WebSocket 연결만 구현한 것이 아니라, room subscribe authorization, Kafka publish
ACK/NACK, DB persisted ACK, Redis Pub/Sub fan-out, DLT replay, reconnect sync, read receipt 정합성을
분리해 검증한 프로젝트입니다.

## 예상 질문과 답변 포인트

| 질문 | 답변 포인트 |
| --- | --- |
| ACK가 오면 메시지가 상대방에게 도착한 건가요? | 아닙니다. ACCEPTED는 Kafka publish accepted, PERSISTED는 DB 저장 완료입니다. receiver 수신은 별도 matrix로 측정해야 합니다. |
| 다른 서버에 붙은 receiver에게 어떻게 전달하나요? | Kafka consumer가 저장 후 Redis Pub/Sub로 event를 발행하고 각 app instance가 payload `roomId` 기준으로 room topic에 fan-out합니다. |
| room ordering은 어디까지 보장하나요? | Kafka key를 `roomId`로 둬 같은 partition 안 offset 순서를 검증합니다. 전역 순서나 모든 클라이언트 수신 순서는 별도 claim이 아닙니다. |
| reconnect 중 놓친 메시지는 어떻게 보정하나요? | `lastReceivedMessageId` 이후 메시지를 REST sync API로 조회합니다. Pub/Sub는 transient fan-out이고 최종 메시지 기준은 DB입니다. |
| N+1은 어떻게 줄였나요? | Entity graph를 DTO 변환 중 순회하지 않고 JPQL projection으로 필요한 필드를 한 번에 가져오도록 바꿨습니다. |
| Redis rate limit의 한계는 무엇인가요? | fixed-window라 초 경계 burst가 생길 수 있습니다. sliding window/token bucket은 개선 과제로 분리했습니다. |

## 피해야 할 표현

- WebSocket delivery completeness를 측정 완료로 말하지 않습니다.
- ACK/PERSISTED를 recipient receive로 설명하지 않습니다.
- connection smoke를 메시지 전달 품질 지표로 확장하지 않습니다.
