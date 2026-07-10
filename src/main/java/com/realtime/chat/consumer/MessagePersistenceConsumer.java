package com.realtime.chat.consumer;

import com.realtime.chat.config.KafkaConfig;
import com.realtime.chat.dto.MessagePersistedNotification;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.service.MessagePersistenceService;
import com.realtime.chat.service.PersistedMessageResult;
import com.realtime.chat.service.RedisPubSubService;
import io.micrometer.core.instrument.Counter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

// DB commit 이후에만 Redis/STOMP fan-out을 시작한다.
@Slf4j
@Component
@RequiredArgsConstructor
public class MessagePersistenceConsumer {

  private final MessagePersistenceService messagePersistenceService;
  @Qualifier("messagesFailedCounter")
  private final Counter messagesFailedCounter;
  private final RedisPubSubService redisPubSubService;

  @KafkaListener(
      topics = KafkaConfig.MESSAGES_TOPIC,
      containerFactory = "persistenceListenerFactory")
  public void consume(ConsumerRecord<String, ChatMessageEvent> record, Acknowledgment ack) {
    ChatMessageEvent event = record.value();
    log.debug(
        "메시지 수신 (persistence): messageKey={}, roomId={}", event.getMessageKey(), event.getRoomId());

    try {
      // @Transactional service가 반환된 시점에는 DB commit이 완료되어 있다.
      PersistedMessageResult result =
          messagePersistenceService.persist(event, record.partition(), record.offset());

      // Redis 실패 시 예외를 재전파한다. Kafka redelivery는 기존 DB row를 찾아 같은 payload를
      // 재발행하고, 클라이언트는 id/clientMessageId로 중복을 제거한다.
      if (result.shouldBroadcast()) {
        redisPubSubService.publishPersistedMessage(result.message());
      }
      redisPubSubService.publishPersisted(
          MessagePersistedNotification.from(result.message(), event.getSenderId()));

      ack.acknowledge();
      log.debug(
          "메시지 처리 완료: messageKey={}, id={}, newlyCreated={}, shouldBroadcast={}",
          event.getMessageKey(),
          result.message().getId(),
          result.newlyCreated(),
          result.shouldBroadcast());

    } catch (Exception e) {
      messagesFailedCounter.increment();
      log.error(
          "메시지 저장 실패: messageKey={}, topic={}, partition={}, offset={}",
          event.getMessageKey(),
          record.topic(),
          record.partition(),
          record.offset(),
          e);
      throw e; // ErrorHandler가 재시도 후 DLT로 보냄
    }
  }
}
