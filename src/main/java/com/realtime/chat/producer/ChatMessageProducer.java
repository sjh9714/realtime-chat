package com.realtime.chat.producer;

import com.realtime.chat.config.KafkaConfig;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.event.ReadReceiptEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class ChatMessageProducer {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    // partition key = roomId로 같은 방 메시지의 순서 보장
    public void sendMessage(ChatMessageEvent event) {
        String key = String.valueOf(event.getRoomId());
        kafkaTemplate.send(KafkaConfig.MESSAGES_TOPIC, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("메시지 발행 실패: roomId={}, messageKey={}", event.getRoomId(), event.getMessageKey(), ex);
                    } else {
                        log.debug("메시지 발행 성공: roomId={}, messageKey={}, partition={}, offset={}",
                                event.getRoomId(), event.getMessageKey(),
                                result.getRecordMetadata().partition(),
                                result.getRecordMetadata().offset());
                    }
                });
    }

    public void sendReadReceipt(ReadReceiptEvent event) {
        String key = String.valueOf(event.getRoomId());
        kafkaTemplate.send(KafkaConfig.READ_RECEIPTS_TOPIC, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("읽음 처리 발행 실패: roomId={}, userId={}", event.getRoomId(), event.getUserId(), ex);
                    }
                });
    }
}
