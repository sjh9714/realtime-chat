package com.realtime.chat;

import com.realtime.chat.config.KafkaConfig;
import com.realtime.chat.domain.MessageType;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.service.DltReplayService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DltReplayServiceTest {

    @Mock
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Test
    @DisplayName("DLT record replay는 원래 topic으로 roomId key를 사용해 재발행하고 Kafka future를 반환한다")
    void replayMessageUsesOriginalTopicAndRoomIdKey() {
        DltReplayService service = new DltReplayService(kafkaTemplate);
        ChatMessageEvent event = event(20L);
        ConsumerRecord<String, ChatMessageEvent> dltRecord =
                new ConsumerRecord<>(KafkaConfig.MESSAGES_DLT, 0, 10L, null, event);
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        given(kafkaTemplate.send(KafkaConfig.MESSAGES_TOPIC, "20", event)).willReturn(future);

        CompletableFuture<SendResult<String, Object>> result = service.replayMessage(dltRecord);

        assertThat(result).isSameAs(future);
        verify(kafkaTemplate).send(KafkaConfig.MESSAGES_TOPIC, "20", event);
    }

    @Test
    @DisplayName("DLT record key가 있으면 replay key로 그대로 사용한다")
    void replayMessageKeepsDltRecordKeyWhenPresent() {
        DltReplayService service = new DltReplayService(kafkaTemplate);
        ChatMessageEvent event = event(20L);
        ConsumerRecord<String, ChatMessageEvent> dltRecord =
                new ConsumerRecord<>(KafkaConfig.MESSAGES_DLT, 0, 10L, "custom-key", event);
        CompletableFuture<SendResult<String, Object>> future = new CompletableFuture<>();
        given(kafkaTemplate.send(KafkaConfig.MESSAGES_TOPIC, "custom-key", event)).willReturn(future);

        CompletableFuture<SendResult<String, Object>> result = service.replayMessage(dltRecord);

        assertThat(result).isSameAs(future);
        verify(kafkaTemplate).send(KafkaConfig.MESSAGES_TOPIC, "custom-key", event);
    }

    private ChatMessageEvent event(Long roomId) {
        return new ChatMessageEvent(
                UUID.randomUUID(),
                roomId,
                10L,
                "유저",
                "DLT replay",
                MessageType.TEXT,
                LocalDateTime.now()
        );
    }
}
