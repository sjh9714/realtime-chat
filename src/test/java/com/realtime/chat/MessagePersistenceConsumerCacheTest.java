package com.realtime.chat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.realtime.chat.config.KafkaConfig;
import com.realtime.chat.consumer.MessagePersistenceConsumer;
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
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.support.Acknowledgment;

@ExtendWith(MockitoExtension.class)
class MessagePersistenceConsumerCacheTest {

  @Mock private MessageRepository messageRepository;

  @Mock private ChatRoomRepository chatRoomRepository;

  @Mock private UserRepository userRepository;

  @Mock private ChatRoomMemberRepository chatRoomMemberRepository;

  @Mock private Counter messagesPersistedCounter;

  @Mock private Counter messagesFailedCounter;

  @Mock private Timer messagesLatencyTimer;

  @Mock private CacheManager cacheManager;

  @Mock private Cache roomsCache;

  @Mock private Acknowledgment acknowledgment;

  @Test
  @DisplayName("메시지 저장 시 해당 room 멤버의 rooms cache만 evict한다")
  void evictsOnlyRoomMembersRoomsCache() {
    MessagePersistenceConsumer consumer = consumer();
    User sender = new User("sender@test.com", "encoded", "보낸사람");
    ChatRoom room = new ChatRoom(null, RoomType.DIRECT, sender);
    ChatMessageEvent event =
        new ChatMessageEvent(
            UUID.randomUUID(), 20L, 10L, "보낸사람", "안녕하세요", MessageType.TEXT, LocalDateTime.now());
    ConsumerRecord<String, ChatMessageEvent> record =
        new ConsumerRecord<>(KafkaConfig.MESSAGES_TOPIC, 0, 0L, "20", event);
    given(messageRepository.existsByMessageKey(event.getMessageKey())).willReturn(false);
    given(chatRoomRepository.findById(20L)).willReturn(Optional.of(room));
    given(userRepository.findById(10L)).willReturn(Optional.of(sender));
    given(messageRepository.save(any(Message.class)))
        .willAnswer(invocation -> invocation.getArgument(0));
    given(chatRoomMemberRepository.findUserIdsByRoomId(20L)).willReturn(List.of(10L, 11L));
    given(cacheManager.getCache("rooms")).willReturn(roomsCache);

    consumer.consume(record, acknowledgment);

    verify(roomsCache).evict(10L);
    verify(roomsCache).evict(11L);
    verify(roomsCache, never()).evict(99L);
    verify(roomsCache, never()).clear();
    verify(acknowledgment).acknowledge();
  }

  private MessagePersistenceConsumer consumer() {
    return new MessagePersistenceConsumer(
        messageRepository,
        chatRoomRepository,
        userRepository,
        chatRoomMemberRepository,
        messagesPersistedCounter,
        messagesFailedCounter,
        messagesLatencyTimer,
        cacheManager);
  }
}
