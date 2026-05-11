package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.realtime.chat.config.KafkaConfig;
import com.realtime.chat.domain.MessageType;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.producer.ChatMessageProducer;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

@ExtendWith(MockitoExtension.class)
class ChatMessageProducerTest {

  @Mock private KafkaTemplate<String, Object> kafkaTemplate;

  @Test
  @DisplayName("메시지는 chat.messages topic에 roomId key로 발행하고 Kafka future를 반환한다")
  void sendMessageUsesMessagesTopicAndRoomIdKey() {
    ChatMessageProducer producer = new ChatMessageProducer(kafkaTemplate);
    ChatMessageEvent event =
        new ChatMessageEvent(
            UUID.randomUUID(), 20L, 10L, "유저", "안녕하세요", MessageType.TEXT, LocalDateTime.now());
    CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
    given(kafkaTemplate.send(KafkaConfig.MESSAGES_TOPIC, "20", event)).willReturn(future);

    CompletableFuture<SendResult<String, Object>> result = producer.sendMessage(event);

    assertThat(result).isSameAs(future);
    verify(kafkaTemplate).send(KafkaConfig.MESSAGES_TOPIC, "20", event);
  }
}
