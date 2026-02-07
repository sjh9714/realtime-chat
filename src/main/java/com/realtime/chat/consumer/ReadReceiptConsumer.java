package com.realtime.chat.consumer;

import com.realtime.chat.config.KafkaConfig;
import com.realtime.chat.event.ReadReceiptEvent;
import com.realtime.chat.service.ReadReceiptService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

// 읽음 처리 Consumer: Kafka → DB/Redis 업데이트
@Slf4j
@Component
@RequiredArgsConstructor
public class ReadReceiptConsumer {

    private final ReadReceiptService readReceiptService;

    @KafkaListener(
            topics = KafkaConfig.READ_RECEIPTS_TOPIC,
            containerFactory = "readReceiptListenerFactory"
    )
    public void consume(ConsumerRecord<String, ReadReceiptEvent> record, Acknowledgment ack) {
        ReadReceiptEvent event = record.value();
        log.debug("읽음 처리 수신: roomId={}, userId={}", event.getRoomId(), event.getUserId());

        try {
            readReceiptService.processReadReceipt(event);
            ack.acknowledge();
        } catch (Exception e) {
            log.error("읽음 처리 실패: roomId={}, userId={}, topic={}, partition={}, offset={}",
                    event.getRoomId(), event.getUserId(), record.topic(), record.partition(), record.offset(), e);
            throw e;
        }
    }
}
