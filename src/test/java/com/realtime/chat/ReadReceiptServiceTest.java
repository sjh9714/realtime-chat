package com.realtime.chat;

import com.realtime.chat.domain.ChatRoomMember;
import com.realtime.chat.event.ReadReceiptEvent;
import com.realtime.chat.producer.ChatMessageProducer;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.MessageRepository;
import com.realtime.chat.service.ReadReceiptService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ReadReceiptServiceTest {

    @Mock
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @Mock
    private MessageRepository messageRepository;

    @Mock
    private ChatMessageProducer chatMessageProducer;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache roomsCache;

    @Test
    @DisplayName("내가 보낸 메시지 제외를 위해 userId 기준으로 unreadCount를 재계산한다")
    void recalculatesUnreadCountWithUserId() {
        ReadReceiptService service = service();
        ChatRoomMember member = member(null, 0, LocalDateTime.of(2026, 5, 10, 10, 0));
        given(chatRoomMemberRepository.findByChatRoomIdAndUserId(20L, 10L)).willReturn(Optional.of(member));
        given(messageRepository.countUnreadMessages(20L, 10L, 100L, member.getJoinedAt())).willReturn(3);
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
        given(chatRoomMemberRepository.findByChatRoomIdAndUserId(20L, 10L)).willReturn(Optional.of(member));
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
        given(chatRoomMemberRepository.findByChatRoomIdAndUserId(20L, 10L)).willReturn(Optional.of(member));

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
                cacheManager
        );
    }

    private ChatRoomMember member(Long lastReadMessageId, int unreadCount, LocalDateTime joinedAt) {
        ChatRoomMember member = new ChatRoomMember(null, null);
        ReflectionTestUtils.setField(member, "lastReadMessageId", lastReadMessageId);
        ReflectionTestUtils.setField(member, "unreadCount", unreadCount);
        ReflectionTestUtils.setField(member, "joinedAt", joinedAt);
        return member;
    }
}
