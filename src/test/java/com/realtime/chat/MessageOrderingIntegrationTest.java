package com.realtime.chat;

import com.realtime.chat.config.KafkaConfig;
import com.realtime.chat.domain.ChatRoom;
import com.realtime.chat.domain.Message;
import com.realtime.chat.domain.MessageType;
import com.realtime.chat.domain.RoomType;
import com.realtime.chat.domain.User;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.ChatRoomRepository;
import com.realtime.chat.repository.MessageRepository;
import com.realtime.chat.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

class MessageOrderingIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private KafkaTemplate<String, Object> kafkaTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @Autowired
    private MessageRepository messageRepository;

    private User user;
    private ChatRoom room;

    @BeforeEach
    void setUp() {
        messageRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        userRepository.deleteAll();

        user = userRepository.save(new User("ordering@test.com", "encoded", "순서유저"));
        room = new ChatRoom(null, RoomType.DIRECT, user);
        room.addMember(user);
        room = chatRoomRepository.saveAndFlush(room);
    }

    @Test
    @DisplayName("같은 roomId 메시지는 동일 partition에 저장되고 Kafka offset 순서가 발행 순서를 유지한다")
    void sameRoomMessagesKeepKafkaOffsetOrder() {
        int messageCount = 5;
        List<String> expectedContents = IntStream.rangeClosed(1, messageCount)
                .mapToObj(index -> "ordered-message-" + index)
                .toList();

        for (String content : expectedContents) {
            ChatMessageEvent event = event(content);
            kafkaTemplate.send(KafkaConfig.MESSAGES_TOPIC, String.valueOf(room.getId()), event);
        }

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            List<Message> messages = messageRepository.findByRoomIdOrderByKafkaOffset(room.getId());
            assertThat(messages).hasSize(messageCount);
            assertThat(messages)
                    .extracting(Message::getKafkaPartition)
                    .containsOnly(messages.get(0).getKafkaPartition());
            assertThat(messages)
                    .extracting(Message::getContent)
                    .containsExactlyElementsOf(expectedContents);
            assertThat(messages)
                    .extracting(Message::getKafkaOffset)
                    .isSorted();
        });
    }

    @Test
    @DisplayName("동일 messageKey 중복 발행 시 같은 room ordering 검증 중에도 DB에는 1건만 저장된다")
    void duplicateMessageKeyIsSavedOnlyOnce() {
        UUID messageKey = UUID.randomUUID();
        ChatMessageEvent event = new ChatMessageEvent(
                messageKey,
                room.getId(),
                user.getId(),
                user.getNickname(),
                "duplicate-ordering-message",
                MessageType.TEXT,
                LocalDateTime.now()
        );

        kafkaTemplate.send(KafkaConfig.MESSAGES_TOPIC, String.valueOf(room.getId()), event);
        kafkaTemplate.send(KafkaConfig.MESSAGES_TOPIC, String.valueOf(room.getId()), event);

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(messageRepository.countByMessageKey(messageKey)).isEqualTo(1));
    }

    private ChatMessageEvent event(String content) {
        return new ChatMessageEvent(
                UUID.randomUUID(),
                room.getId(),
                user.getId(),
                user.getNickname(),
                content,
                MessageType.TEXT,
                LocalDateTime.now()
        );
    }
}
