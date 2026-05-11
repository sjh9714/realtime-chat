package com.realtime.chat.service;

import com.realtime.chat.config.KafkaConfig;
import com.realtime.chat.event.ChatMessageEvent;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

@Slf4j
@Service
@RequiredArgsConstructor
public class DltReplayService {

  private final KafkaTemplate<String, Object> kafkaTemplate;

  public CompletableFuture<SendResult<String, Object>> replayMessage(
      ConsumerRecord<String, ChatMessageEvent> dltRecord) {
    ChatMessageEvent event = dltRecord.value();
    String key = dltRecord.key() != null ? dltRecord.key() : String.valueOf(event.getRoomId());

    log.info(
        "DLT 메시지 replay 시작: messageKey={}, dltTopic={}, dltPartition={}, dltOffset={},"
            + " targetTopic={}, key={}",
        event.getMessageKey(),
        dltRecord.topic(),
        dltRecord.partition(),
        dltRecord.offset(),
        KafkaConfig.MESSAGES_TOPIC,
        key);

    CompletableFuture<SendResult<String, Object>> future =
        kafkaTemplate.send(KafkaConfig.MESSAGES_TOPIC, key, event);

    future.whenComplete(
        (result, ex) -> {
          if (ex != null) {
            log.error(
                "DLT 메시지 replay 실패: messageKey={}, dltTopic={}, dltPartition={}, dltOffset={},"
                    + " targetTopic={}, key={}",
                event.getMessageKey(),
                dltRecord.topic(),
                dltRecord.partition(),
                dltRecord.offset(),
                KafkaConfig.MESSAGES_TOPIC,
                key,
                ex);
            return;
          }

          log.info(
              "DLT 메시지 replay 성공: messageKey={}, dltTopic={}, dltPartition={}, dltOffset={},"
                  + " targetTopic={}, key={}, targetPartition={}, targetOffset={}",
              event.getMessageKey(),
              dltRecord.topic(),
              dltRecord.partition(),
              dltRecord.offset(),
              KafkaConfig.MESSAGES_TOPIC,
              key,
              result.getRecordMetadata().partition(),
              result.getRecordMetadata().offset());
        });

    return future;
  }

  public CompletableFuture<SendResult<String, Object>> replayMessage(ChatMessageEvent event) {
    String key = String.valueOf(event.getRoomId());
    log.info(
        "DLT 메시지 replay 시작: messageKey={}, targetTopic={}, key={}",
        event.getMessageKey(),
        KafkaConfig.MESSAGES_TOPIC,
        key);

    CompletableFuture<SendResult<String, Object>> future =
        kafkaTemplate.send(KafkaConfig.MESSAGES_TOPIC, key, event);

    future.whenComplete(
        (result, ex) -> {
          if (ex != null) {
            log.error(
                "DLT 메시지 replay 실패: messageKey={}, targetTopic={}, key={}",
                event.getMessageKey(),
                KafkaConfig.MESSAGES_TOPIC,
                key,
                ex);
            return;
          }

          log.info(
              "DLT 메시지 replay 성공: messageKey={}, targetTopic={}, key={}, targetPartition={},"
                  + " targetOffset={}",
              event.getMessageKey(),
              KafkaConfig.MESSAGES_TOPIC,
              key,
              result.getRecordMetadata().partition(),
              result.getRecordMetadata().offset());
        });

    return future;
  }
}
