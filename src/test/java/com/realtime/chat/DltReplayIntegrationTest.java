package com.realtime.chat;

import com.realtime.chat.config.KafkaConfig;
import com.realtime.chat.domain.MessageType;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.repository.MessageRepository;
import com.realtime.chat.service.DltReplayService;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.serializer.JsonDeserializer;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class DltReplayIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private DltReplayService dltReplayService;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM messages");
        jdbcTemplate.update("DELETE FROM chat_room_members");
        jdbcTemplate.update("DELETE FROM chat_rooms");
        jdbcTemplate.update("DELETE FROM users");
    }

    @Test
    @DisplayName("consumer 실패 메시지는 DLT로 이동하고 manual replay 후 저장되며 재실행해도 중복 저장되지 않는다")
    void failedMessageCanBeReplayedFromDltWithoutDuplicateSave() throws Exception {
        long roomId = 9001L;
        long senderId = 9002L;
        UUID messageKey = UUID.randomUUID();
        ChatMessageEvent failedEvent = new ChatMessageEvent(
                messageKey,
                roomId,
                senderId,
                "DLT 유저",
                "DLT replay 대상",
                MessageType.TEXT,
                LocalDateTime.now()
        );

        try (KafkaConsumer<String, ChatMessageEvent> dltConsumer = newDltConsumer()) {
            dltConsumer.subscribe(List.of(KafkaConfig.MESSAGES_DLT));
            kafkaTemplate.send(KafkaConfig.MESSAGES_TOPIC, String.valueOf(roomId), failedEvent).get(10, TimeUnit.SECONDS);

            ConsumerRecord<String, ChatMessageEvent> dltRecord = awaitDltRecord(dltConsumer, messageKey);
            assertThat(dltRecord.value().getMessageKey()).isEqualTo(messageKey);

            insertReplayDependencies(roomId, senderId);
            dltReplayService.replayMessage(dltRecord).get(10, TimeUnit.SECONDS);

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(messageRepository.countByMessageKey(messageKey)).isEqualTo(1));

            dltReplayService.replayMessage(dltRecord).get(10, TimeUnit.SECONDS);

            await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                    assertThat(messageRepository.countByMessageKey(messageKey)).isEqualTo(1));
        }
    }

    private KafkaConsumer<String, ChatMessageEvent> newDltConsumer() {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-replay-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class,
                JsonDeserializer.TRUSTED_PACKAGES, "com.realtime.chat.event",
                JsonDeserializer.VALUE_DEFAULT_TYPE, ChatMessageEvent.class.getName()
        );
        return new KafkaConsumer<>(props);
    }

    private ConsumerRecord<String, ChatMessageEvent> awaitDltRecord(
            KafkaConsumer<String, ChatMessageEvent> consumer,
            UUID messageKey) {
        AtomicReference<ConsumerRecord<String, ChatMessageEvent>> found = new AtomicReference<>();
        await().atMost(20, TimeUnit.SECONDS).untilAsserted(() -> {
            consumer.poll(Duration.ofMillis(500))
                    .forEach(record -> {
                        ChatMessageEvent value = record.value();
                        if (value != null && messageKey.equals(value.getMessageKey())) {
                            found.set(record);
                        }
                    });
            assertThat(found.get()).isNotNull();
        });
        return found.get();
    }

    private void insertReplayDependencies(long roomId, long senderId) {
        jdbcTemplate.update("""
                INSERT INTO users (id, email, password, nickname, status, created_at)
                VALUES (?, ?, ?, ?, 'OFFLINE', NOW())
                """, senderId, "dlt-user-" + senderId + "@test.com", "encoded", "DLT 유저");
        jdbcTemplate.update("""
                INSERT INTO chat_rooms (id, name, type, created_by, created_at)
                VALUES (?, NULL, 'DIRECT', ?, NOW())
                """, roomId, senderId);
        jdbcTemplate.update("""
                INSERT INTO chat_room_members (room_id, user_id, unread_count, joined_at)
                VALUES (?, ?, 0, NOW())
                """, roomId, senderId);
    }
}
