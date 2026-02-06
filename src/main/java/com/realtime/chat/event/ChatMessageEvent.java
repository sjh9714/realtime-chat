package com.realtime.chat.event;

import com.realtime.chat.domain.MessageType;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

// Kafka 메시지 스키마: 채팅 메시지
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageEvent {

    private UUID messageKey;
    private Long roomId;
    private Long senderId;
    private String senderNickname;
    private String content;
    private MessageType type;
    private LocalDateTime timestamp;

    public static ChatMessageEvent of(Long roomId, Long senderId, String senderNickname,
                                       String content, MessageType type) {
        return new ChatMessageEvent(
                UUID.randomUUID(),
                roomId,
                senderId,
                senderNickname,
                content,
                type,
                LocalDateTime.now()
        );
    }
}
