package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

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
import com.realtime.chat.service.MessagePersistenceService;
import com.realtime.chat.service.PersistenceFailureProbe;
import com.realtime.chat.service.PersistedMessageResult;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class MessagePersistenceServiceTest {

  @Mock private MessageRepository messageRepository;
  @Mock private ChatRoomRepository chatRoomRepository;
  @Mock private UserRepository userRepository;
  @Mock private ChatRoomMemberRepository chatRoomMemberRepository;
  @Mock private Counter messagesPersistedCounter;
  @Mock private Timer messagesLatencyTimer;
  @Mock private Counter roomsCacheEvictionsCounter;
  @Mock private CacheManager cacheManager;
  @Mock private Cache roomsCache;
  @Mock private PersistenceFailureProbe failureProbe;

  @Test
  @DisplayName("새 메시지는 DB id/clientMessageId를 가진 응답으로 commit 경계 밖에 반환된다")
  void persistsMessageAndReturnsStableIdentity() {
    User sender = user(10L);
    ChatRoom room = room(20L, sender);
    UUID messageKey = UUID.randomUUID();
    UUID clientMessageId = UUID.randomUUID();
    ChatMessageEvent event = event(messageKey, clientMessageId);

    given(chatRoomRepository.findById(20L)).willReturn(Optional.of(room));
    given(userRepository.findById(10L)).willReturn(Optional.of(sender));
    given(chatRoomMemberRepository.existsByChatRoomIdAndUserId(20L, 10L)).willReturn(true);
    given(messageRepository.saveAndFlush(any(Message.class)))
        .willAnswer(
            invocation -> {
              Message message = invocation.getArgument(0);
              ReflectionTestUtils.setField(message, "id", 100L);
              ReflectionTestUtils.setField(message, "createdAt", LocalDateTime.now());
              return message;
            });
    given(chatRoomMemberRepository.findUserIdsByRoomId(20L)).willReturn(List.of(10L, 11L));
    given(cacheManager.getCache("rooms")).willReturn(roomsCache);

    PersistedMessageResult result = service().persist(event, 2, 41L);

    assertThat(result.newlyCreated()).isTrue();
    assertThat(result.shouldBroadcast()).isTrue();
    assertThat(result.message().getId()).isEqualTo(100L);
    assertThat(result.message().getClientMessageId()).isEqualTo(clientMessageId);
    assertThat(result.message().getMessageKey()).isEqualTo(messageKey);
    verify(chatRoomMemberRepository).incrementUnreadCountForOtherMembers(20L, 10L);
    verify(roomsCache).evict(10L);
    verify(roomsCache).evict(11L);
    verify(messagesPersistedCounter).increment();
  }

  @Test
  @DisplayName("Kafka redelivery는 기존 row를 반환하고 unread/cache 부수 효과를 반복하지 않는다")
  void redeliveryReturnsExistingMessageWithoutRepeatingSideEffects() {
    User sender = user(10L);
    ChatRoom room = room(20L, sender);
    UUID messageKey = UUID.randomUUID();
    UUID clientMessageId = UUID.randomUUID();
    Message existing =
        new Message(messageKey, clientMessageId, room, sender, "안녕하세요", MessageType.TEXT);
    ReflectionTestUtils.setField(existing, "id", 100L);
    ReflectionTestUtils.setField(existing, "createdAt", LocalDateTime.now());
    given(messageRepository.findByMessageKey(messageKey)).willReturn(Optional.of(existing));

    PersistedMessageResult result = service().persist(event(messageKey, clientMessageId), 2, 42L);

    assertThat(result.newlyCreated()).isFalse();
    assertThat(result.shouldBroadcast()).isTrue();
    assertThat(result.message().getId()).isEqualTo(100L);
    verify(messageRepository, never()).saveAndFlush(any(Message.class));
    verify(chatRoomMemberRepository, never()).incrementUnreadCountForOtherMembers(any(), any());
    verify(cacheManager, never()).getCache("rooms");
    verify(messagesPersistedCounter, never()).increment();
  }

  @Test
  @DisplayName("같은 clientMessageId의 새 publish는 DB와 receiver broadcast를 중복하지 않는다")
  void clientRetryReturnsPersistedIdentityWithoutReceiverRebroadcast() {
    User sender = user(10L);
    ChatRoom room = room(20L, sender);
    UUID storedMessageKey = UUID.randomUUID();
    UUID retryMessageKey = UUID.randomUUID();
    UUID clientMessageId = UUID.randomUUID();
    Message existing =
        new Message(storedMessageKey, clientMessageId, room, sender, "안녕하세요", MessageType.TEXT);
    ReflectionTestUtils.setField(existing, "id", 100L);
    ReflectionTestUtils.setField(existing, "createdAt", LocalDateTime.now());
    given(messageRepository.findBySenderIdAndClientMessageId(10L, clientMessageId))
        .willReturn(Optional.of(existing));

    PersistedMessageResult result =
        service().persist(event(retryMessageKey, clientMessageId), 2, 43L);

    assertThat(result.newlyCreated()).isFalse();
    assertThat(result.shouldBroadcast()).isFalse();
    assertThat(result.message().getMessageKey()).isEqualTo(storedMessageKey);
    verify(messageRepository, never()).saveAndFlush(any(Message.class));
    verify(chatRoomMemberRepository, never()).incrementUnreadCountForOtherMembers(any(), any());
  }

  private MessagePersistenceService service() {
    return new MessagePersistenceService(
        messageRepository,
        chatRoomRepository,
        userRepository,
        chatRoomMemberRepository,
        messagesPersistedCounter,
        messagesLatencyTimer,
        roomsCacheEvictionsCounter,
        cacheManager,
        failureProbe);
  }

  private User user(Long id) {
    User user = new User("sender@test.com", "encoded", "보낸사람");
    ReflectionTestUtils.setField(user, "id", id);
    return user;
  }

  private ChatRoom room(Long id, User sender) {
    ChatRoom room = new ChatRoom(null, RoomType.DIRECT, sender);
    ReflectionTestUtils.setField(room, "id", id);
    return room;
  }

  private ChatMessageEvent event(UUID messageKey, UUID clientMessageId) {
    return new ChatMessageEvent(
        messageKey,
        20L,
        10L,
        "보낸사람",
        "안녕하세요",
        MessageType.TEXT,
        clientMessageId,
        LocalDateTime.now());
  }
}
