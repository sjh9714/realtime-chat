package com.realtime.chat.service;

import com.realtime.chat.common.BusinessException;
import com.realtime.chat.domain.Message;
import com.realtime.chat.dto.MessagePageResponse;
import com.realtime.chat.dto.MessageResponse;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.MessageRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageRepository messageRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;

    // 커서 기반 페이지네이션으로 메시지 이력 조회
    @Transactional(readOnly = true)
    public MessagePageResponse getMessages(Long userId, Long roomId, Long cursor, int size) {
        // 채팅방 멤버인지 확인
        if (!chatRoomMemberRepository.existsByChatRoomIdAndUserId(roomId, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "채팅방에 참여하지 않은 사용자입니다.");
        }

        // size+1개를 조회하여 hasMore 판단
        int fetchSize = size + 1;
        List<Message> messages;

        if (cursor == null) {
            messages = messageRepository.findByRoomIdLatest(roomId, fetchSize);
        } else {
            messages = messageRepository.findByRoomIdWithCursor(roomId, cursor, fetchSize);
        }

        boolean hasMore = messages.size() > size;
        if (hasMore) {
            messages = messages.subList(0, size);
        }

        List<MessageResponse> messageResponses = messages.stream()
                .map(MessageResponse::from)
                .toList();

        Long nextCursor = hasMore ? messages.get(messages.size() - 1).getId() : null;

        return new MessagePageResponse(messageResponses, hasMore, nextCursor);
    }
}
