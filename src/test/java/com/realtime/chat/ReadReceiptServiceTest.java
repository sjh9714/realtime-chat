package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.realtime.chat.common.BusinessException;
import com.realtime.chat.domain.ChatRoom;
import com.realtime.chat.domain.ChatRoomMember;
import com.realtime.chat.domain.Message;
import com.realtime.chat.domain.MessageType;
import com.realtime.chat.domain.RoomType;
import com.realtime.chat.domain.User;
import com.realtime.chat.event.ReadReceiptEvent;
import com.realtime.chat.producer.ChatMessageProducer;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.MessageRepository;
import com.realtime.chat.service.ReadReceiptService;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReadReceiptServiceTest {

  @Mock private ChatRoomMemberRepository chatRoomMemberRepository;

  @Mock private MessageRepository messageRepository;

  @Mock private ChatMessageProducer chatMessageProducer;

  @Mock private StringRedisTemplate redisTemplate;

  @Mock private ValueOperations<String, String> valueOperations;

  @Mock private CacheManager cacheManager;

  @Mock private Cache roomsCache;

  @Test
  @DisplayName("정상 room 메시지로 읽음 처리하면 read receipt 이벤트를 발행한다")
  void markAsReadPublishesEventForMessageInRoom() {
    ReadReceiptService service = service();
    LocalDateTime joinedAt = LocalDateTime.of(2026, 5, 10, 10, 0);
    ChatRoomMember member = member(null, 0, joinedAt);
    given(chatRoomMemberRepository.findByChatRoomIdAndUserId(20L, 10L))
        .willReturn(Optional.of(member));
    given(messageRepository.findByIdAndChatRoomId(100L, 20L))
        .willReturn(Optional.of(message(100L, 20L, LocalDateTime.of(2026, 5, 10, 10, 5))));

    service.markAsRead(10L, 20L, 100L);

    verify(chatMessageProducer)
        .sendReadReceipt(
            argThat(
                event ->
                    event.getRoomId().equals(20L)
                        && event.getUserId().equals(10L)
                        && event.getLastReadMessageId().equals(100L)));
  }

  @Test
  @DisplayName("lastReadMessageId가 null이면 읽음 처리를 거부한다")
  void markAsReadRejectsNullMessageId() {
    ReadReceiptService service = service();
    ChatRoomMember member = member(null, 0, LocalDateTime.of(2026, 5, 10, 10, 0));
    given(chatRoomMemberRepository.findByChatRoomIdAndUserId(20L, 10L))
        .willReturn(Optional.of(member));

    assertThatThrownBy(() -> service.markAsRead(10L, 20L, null))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    verify(chatMessageProducer, never()).sendReadReceipt(argThat(event -> true));
  }

  @Test
  @DisplayName("lastReadMessageId가 존재하지 않으면 읽음 처리를 거부한다")
  void markAsReadRejectsUnknownMessageId() {
    ReadReceiptService service = service();
    ChatRoomMember member = member(null, 0, LocalDateTime.of(2026, 5, 10, 10, 0));
    given(chatRoomMemberRepository.findByChatRoomIdAndUserId(20L, 10L))
        .willReturn(Optional.of(member));
    given(messageRepository.findByIdAndChatRoomId(999L, 20L)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.markAsRead(10L, 20L, 999L))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    verify(chatMessageProducer, never()).sendReadReceipt(argThat(event -> true));
  }

  @Test
  @DisplayName("다른 room의 메시지 ID로 unreadCount를 낮출 수 없다")
  void markAsReadRejectsMessageFromAnotherRoom() {
    ReadReceiptService service = service();
    ChatRoomMember member = member(null, 0, LocalDateTime.of(2026, 5, 10, 10, 0));
    given(chatRoomMemberRepository.findByChatRoomIdAndUserId(20L, 10L))
        .willReturn(Optional.of(member));
    given(messageRepository.findByIdAndChatRoomId(100L, 20L)).willReturn(Optional.empty());

    assertThatThrownBy(() -> service.markAsRead(10L, 20L, 100L))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    verify(chatMessageProducer, never()).sendReadReceipt(argThat(event -> true));
  }

  @Test
  @DisplayName("참여 전에 생성된 메시지를 lastReadMessageId로 사용할 수 없다")
  void markAsReadRejectsMessageBeforeJoinedAt() {
    ReadReceiptService service = service();
    LocalDateTime joinedAt = LocalDateTime.of(2026, 5, 10, 10, 0);
    ChatRoomMember member = member(null, 0, joinedAt);
    given(chatRoomMemberRepository.findByChatRoomIdAndUserId(20L, 10L))
        .willReturn(Optional.of(member));
    given(messageRepository.findByIdAndChatRoomId(100L, 20L))
        .willReturn(Optional.of(message(100L, 20L, LocalDateTime.of(2026, 5, 10, 9, 59))));

    assertThatThrownBy(() -> service.markAsRead(10L, 20L, 100L))
        .isInstanceOfSatisfying(
            BusinessException.class,
            ex -> assertThat(ex.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    verify(chatMessageProducer, never()).sendReadReceipt(argThat(event -> true));
  }

  @Test
  @DisplayName("내가 보낸 메시지 제외를 위해 userId 기준으로 unreadCount를 재계산한다")
  void recalculatesUnreadCountWithUserId() {
    ReadReceiptService service = service();
    ChatRoomMember member = member(null, 0, LocalDateTime.of(2026, 5, 10, 10, 0));
    given(chatRoomMemberRepository.findByChatRoomIdAndUserId(20L, 10L))
        .willReturn(Optional.of(member));
    given(messageRepository.countUnreadMessages(20L, 10L, 100L, member.getJoinedAt()))
        .willReturn(3);
    given(redisTemplate.opsForValue()).willReturn(valueOperations);
    given(cacheManager.getCache("rooms")).willReturn(roomsCache);

    service.processReadReceipt(new ReadReceiptEvent(20L, 10L, 100L, LocalDateTime.now()));

    assertThat(member.getLastReadMessageId()).isEqualTo(100L);
    assertThat(member.getUnreadCount()).isEqualTo(3);
    verify(messageRepository).countUnreadMessages(20L, 10L, 100L, member.getJoinedAt());
    verify(valueOperations).set("unread:room:20:user:10", "3");
    verify(roomsCache).evict(10L);
  }

  @Test
  @DisplayName("joinedAt 이전 메시지 제외를 위해 참여 시각 기준으로 unreadCount를 재계산한다")
  void recalculatesUnreadCountWithJoinedAt() {
    ReadReceiptService service = service();
    LocalDateTime joinedAt = LocalDateTime.of(2026, 5, 10, 11, 0);
    ChatRoomMember member = member(null, 0, joinedAt);
    given(chatRoomMemberRepository.findByChatRoomIdAndUserId(20L, 10L))
        .willReturn(Optional.of(member));
    given(messageRepository.countUnreadMessages(20L, 10L, 100L, joinedAt)).willReturn(1);
    given(redisTemplate.opsForValue()).willReturn(valueOperations);
    given(cacheManager.getCache("rooms")).willReturn(roomsCache);

    service.processReadReceipt(new ReadReceiptEvent(20L, 10L, 100L, LocalDateTime.now()));

    assertThat(member.getUnreadCount()).isEqualTo(1);
    verify(messageRepository).countUnreadMessages(20L, 10L, 100L, joinedAt);
  }

  @Test
  @DisplayName("같은 read receipt를 중복 처리해도 상태를 다시 갱신하지 않는다")
  void duplicateReadReceiptIsIdempotent() {
    ReadReceiptService service = service();
    ChatRoomMember member = member(100L, 5, LocalDateTime.of(2026, 5, 10, 10, 0));
    given(chatRoomMemberRepository.findByChatRoomIdAndUserId(20L, 10L))
        .willReturn(Optional.of(member));

    service.processReadReceipt(new ReadReceiptEvent(20L, 10L, 100L, LocalDateTime.now()));

    assertThat(member.getLastReadMessageId()).isEqualTo(100L);
    assertThat(member.getUnreadCount()).isEqualTo(5);
    verify(messageRepository, never()).countUnreadMessages(20L, 10L, 100L, member.getJoinedAt());
    verify(redisTemplate, never()).opsForValue();
    verify(cacheManager, never()).getCache(anyString());
  }

  private ReadReceiptService service() {
    return new ReadReceiptService(
        chatRoomMemberRepository,
        messageRepository,
        chatMessageProducer,
        redisTemplate,
        cacheManager);
  }

  private ChatRoomMember member(Long lastReadMessageId, int unreadCount, LocalDateTime joinedAt) {
    ChatRoomMember member = new ChatRoomMember(null, null);
    ReflectionTestUtils.setField(member, "lastReadMessageId", lastReadMessageId);
    ReflectionTestUtils.setField(member, "unreadCount", unreadCount);
    ReflectionTestUtils.setField(member, "joinedAt", joinedAt);
    return member;
  }

  private Message message(Long messageId, Long roomId, LocalDateTime createdAt) {
    User sender = new User("sender-" + messageId + "@test.com", "encoded", "sender");
    ChatRoom room = new ChatRoom(null, RoomType.DIRECT, sender);
    ReflectionTestUtils.setField(room, "id", roomId);
    Message message = new Message(UUID.randomUUID(), room, sender, "hello", MessageType.TEXT);
    ReflectionTestUtils.setField(message, "id", messageId);
    ReflectionTestUtils.setField(message, "createdAt", createdAt);
    return message;
  }
}
