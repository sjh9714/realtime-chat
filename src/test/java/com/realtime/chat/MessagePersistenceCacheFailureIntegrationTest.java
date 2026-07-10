package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.realtime.chat.domain.ChatRoom;
import com.realtime.chat.domain.MessageType;
import com.realtime.chat.domain.RoomType;
import com.realtime.chat.domain.User;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.ChatRoomRepository;
import com.realtime.chat.repository.MessageRepository;
import com.realtime.chat.repository.UserRepository;
import com.realtime.chat.service.MessagePersistenceService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

class MessagePersistenceCacheFailureIntegrationTest extends BaseIntegrationTest {

  @Autowired private MessagePersistenceService messagePersistenceService;
  @Autowired private MessageRepository messageRepository;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatRoomMemberRepository chatRoomMemberRepository;
  @Autowired private UserRepository userRepository;

  @MockitoBean private CacheManager cacheManager;
  private Cache roomsCache;

  @BeforeEach
  void setUp() {
    roomsCache = mock(Cache.class);
    messageRepository.deleteAll();
    chatRoomMemberRepository.deleteAll();
    chatRoomRepository.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  @DisplayName("afterCommit cache eviction 실패는 저장된 message와 unread 증가를 되돌리지 않는다")
  void cacheFailureCannotRollbackCommittedMessageAndUnreadCount() {
    User sender = userRepository.save(new User("cache-sender@test.com", "encoded", "보낸사람"));
    User receiver = userRepository.save(new User("cache-receiver@test.com", "encoded", "받는사람"));
    ChatRoom room = new ChatRoom(null, RoomType.DIRECT, sender);
    room.addMember(sender);
    room.addMember(receiver);
    room = chatRoomRepository.saveAndFlush(room);
    UUID messageKey = UUID.randomUUID();
    UUID clientMessageId = UUID.randomUUID();
    given(cacheManager.getCache("rooms")).willReturn(roomsCache);
    doThrow(new IllegalStateException("redis cache unavailable")).when(roomsCache).evict(receiver.getId());

    messagePersistenceService.persist(
        new ChatMessageEvent(
            messageKey,
            room.getId(),
            sender.getId(),
            sender.getNickname(),
            "commit survives cache failure",
            MessageType.TEXT,
            clientMessageId,
            LocalDateTime.now()),
        0,
        1L);

    assertThat(messageRepository.findByMessageKey(messageKey)).isPresent();
    assertThat(
            chatRoomMemberRepository
                .findByChatRoomIdAndUserId(room.getId(), receiver.getId())
                .orElseThrow()
                .getUnreadCount())
        .isEqualTo(1);
    verify(roomsCache).evict(receiver.getId());
  }
}
