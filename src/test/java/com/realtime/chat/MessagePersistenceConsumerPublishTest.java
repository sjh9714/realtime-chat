package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.realtime.chat.consumer.MessagePersistenceConsumer;
import com.realtime.chat.domain.MessageType;
import com.realtime.chat.dto.MessagePublishStatus;
import com.realtime.chat.dto.MessageResponse;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.service.MessagePersistenceService;
import com.realtime.chat.service.PersistedMessageResult;
import com.realtime.chat.service.RedisPubSubService;
import io.micrometer.core.instrument.Counter;
import java.time.LocalDateTime;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class MessagePersistenceConsumerPublishTest {

  @Mock private MessagePersistenceService persistenceService;
  @Mock private RedisPubSubService redisPubSubService;
  @Mock private Counter messagesFailedCounter;
  @Mock private Acknowledgment ack;

  @Test
  @DisplayName("DB commit 후 Redis publish 실패 시 Kafka ack를 하지 않고 예외를 재전파한다")
  void redisPublishFailureIsNotAcknowledged() {
    MessagePersistenceConsumer consumer =
        new MessagePersistenceConsumer(persistenceService, messagesFailedCounter, redisPubSubService);
    UUID messageKey = UUID.randomUUID();
    UUID clientMessageId = UUID.randomUUID();
    ChatMessageEvent event =
        new ChatMessageEvent(
            messageKey,
            20L,
            10L,
            "sender",
            "hello",
            MessageType.TEXT,
            clientMessageId,
            LocalDateTime.now());
    ConsumerRecord<String, ChatMessageEvent> record =
        new ConsumerRecord<>("chat.messages", 0, 15L, "20", event);
    MessageResponse persisted =
        new MessageResponse(
            42L,
            messageKey,
            clientMessageId,
            20L,
            10L,
            "sender",
            "hello",
            MessageType.TEXT,
            MessagePublishStatus.PERSISTED,
            LocalDateTime.now());
    given(persistenceService.persist(event, 0, 15L))
        .willReturn(new PersistedMessageResult(persisted, true, true));
    willThrow(new IllegalStateException("Redis publish failed"))
        .given(redisPubSubService)
        .publishPersistedMessage(persisted);

    assertThatThrownBy(() -> consumer.consume(record, ack))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Redis publish failed");
    verify(ack, never()).acknowledge();
    verify(messagesFailedCounter).increment();
  }

  @Test
  @DisplayName("clientMessageId retry는 receiver broadcast 없이 sender PERSISTED만 재확인한다")
  void clientRetrySkipsReceiverRebroadcast() {
    MessagePersistenceConsumer consumer =
        new MessagePersistenceConsumer(persistenceService, messagesFailedCounter, redisPubSubService);
    UUID messageKey = UUID.randomUUID();
    UUID clientMessageId = UUID.randomUUID();
    ChatMessageEvent event =
        new ChatMessageEvent(
            UUID.randomUUID(),
            20L,
            10L,
            "sender",
            "hello",
            MessageType.TEXT,
            clientMessageId,
            LocalDateTime.now());
    ConsumerRecord<String, ChatMessageEvent> record =
        new ConsumerRecord<>("chat.messages", 0, 16L, "20", event);
    MessageResponse persisted =
        new MessageResponse(
            42L,
            messageKey,
            clientMessageId,
            20L,
            10L,
            "sender",
            "hello",
            MessageType.TEXT,
            MessagePublishStatus.PERSISTED,
            LocalDateTime.now());
    given(persistenceService.persist(event, 0, 16L))
        .willReturn(new PersistedMessageResult(persisted, false, false));

    consumer.consume(record, ack);

    verify(redisPubSubService, never()).publishPersistedMessage(any());
    verify(redisPubSubService).publishPersisted(any());
    verify(ack).acknowledge();
  }
}
