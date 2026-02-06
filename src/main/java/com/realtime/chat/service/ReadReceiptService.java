package com.realtime.chat.service;

import com.realtime.chat.common.BusinessException;
import com.realtime.chat.domain.ChatRoomMember;
import com.realtime.chat.event.ReadReceiptEvent;
import com.realtime.chat.producer.ChatMessageProducer;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ReadReceiptService {

    private static final String UNREAD_COUNT_KEY = "unread:room:%d:user:%d";

    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final MessageRepository messageRepository;
    private final ChatMessageProducer chatMessageProducer;
    private final StringRedisTemplate redisTemplate;

    // 읽음 처리 요청 → Kafka로 발행
    public void markAsRead(Long userId, Long roomId, Long lastReadMessageId) {
        if (!chatRoomMemberRepository.existsByChatRoomIdAndUserId(roomId, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "채팅방에 참여하지 않은 사용자입니다.");
        }

        ReadReceiptEvent event = ReadReceiptEvent.of(roomId, userId, lastReadMessageId);
        chatMessageProducer.sendReadReceipt(event);
    }

    // Kafka Consumer에서 호출: 읽음 처리 실행
    @Transactional
    public void processReadReceipt(ReadReceiptEvent event) {
        ChatRoomMember member = chatRoomMemberRepository
                .findByChatRoomIdAndUserId(event.getRoomId(), event.getUserId())
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "채팅방 멤버를 찾을 수 없습니다."));

        // lastReadMessageId 업데이트 (더 큰 값으로만 업데이트)
        if (member.getLastReadMessageId() == null || event.getLastReadMessageId() > member.getLastReadMessageId()) {
            member.updateLastReadMessageId(event.getLastReadMessageId());

            // DB에서 실제 unreadCount 재계산
            int unreadCount = messageRepository.countUnreadMessages(event.getRoomId(), event.getLastReadMessageId());
            member.updateUnreadCount(unreadCount);

            // Redis 캐시 업데이트
            String key = String.format(UNREAD_COUNT_KEY, event.getRoomId(), event.getUserId());
            redisTemplate.opsForValue().set(key, String.valueOf(unreadCount));

            log.debug("읽음 처리 완료: roomId={}, userId={}, unreadCount={}", event.getRoomId(), event.getUserId(), unreadCount);
        }
    }

    // unreadCount 조회 (Redis 캐시 + DB fallback)
    public int getUnreadCount(Long roomId, Long userId) {
        String key = String.format(UNREAD_COUNT_KEY, roomId, userId);
        String cached = redisTemplate.opsForValue().get(key);

        if (cached != null) {
            return Integer.parseInt(cached);
        }

        // Redis 캐시 미스 → DB에서 재계산
        ChatRoomMember member = chatRoomMemberRepository
                .findByChatRoomIdAndUserId(roomId, userId)
                .orElse(null);

        if (member == null) {
            return 0;
        }

        int unreadCount = member.getUnreadCount();
        redisTemplate.opsForValue().set(key, String.valueOf(unreadCount));
        return unreadCount;
    }
}
