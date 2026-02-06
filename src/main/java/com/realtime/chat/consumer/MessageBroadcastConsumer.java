package com.realtime.chat.consumer;

import com.realtime.chat.config.KafkaConfig;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.service.RedisPubSubService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

// Consumer Group 2: Kafka → Redis Pub/Sub → WebSocket 브로드캐스트
@Slf4j
@Component
@RequiredArgsConstructor
public class MessageBroadcastConsumer {

    private final RedisPubSubService redisPubSubService;

    @KafkaListener(
            topics = KafkaConfig.MESSAGES_TOPIC,
            containerFactory = "broadcastListenerFactory"
    )
    public void consume(ConsumerRecord<String, ChatMessageEvent> record, Acknowledgment ack) {
        ChatMessageEvent event = record.value();
        log.debug("메시지 수신 (broadcast): messageKey={}, roomId={}", event.getMessageKey(), event.getRoomId());

        try {
            redisPubSubService.publish(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("브로드캐스트 실패: messageKey={}", event.getMessageKey(), e);
            throw e;
        }
    }
}
