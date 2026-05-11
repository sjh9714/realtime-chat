package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.realtime.chat.consumer.MessageBroadcastConsumer;
import com.realtime.chat.domain.MessageType;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.service.RedisPubSubService;
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
class MessageBroadcastConsumerTest {

  @Mock private RedisPubSubService redisPubSubService;

  @Mock private Acknowledgment ack;

  @Test
  @DisplayName("Redis publish 실패 시 Kafka ack를 하지 않고 예외를 재전파한다")
  void redisPublishFailureIsNotAcknowledged() {
    MessageBroadcastConsumer consumer = new MessageBroadcastConsumer(redisPubSubService);
    ChatMessageEvent event =
        new ChatMessageEvent(
            UUID.randomUUID(), 20L, 10L, "sender", "hello", MessageType.TEXT, LocalDateTime.now());
    ConsumerRecord<String, ChatMessageEvent> record =
        new ConsumerRecord<>("chat.messages", 0, 15L, "20", event);
    willThrow(new IllegalStateException("Redis publish failed"))
        .given(redisPubSubService)
        .publish(event);

    assertThatThrownBy(() -> consumer.consume(record, ack))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Redis publish failed");
    verify(ack, never()).acknowledge();
  }
}
