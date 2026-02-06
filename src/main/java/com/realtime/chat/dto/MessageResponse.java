package com.realtime.chat.dto;

import com.realtime.chat.domain.Message;
import com.realtime.chat.domain.MessageType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@AllArgsConstructor
public class MessageResponse {

    private Long id;
    private UUID messageKey;
    private Long roomId;
    private Long senderId;
    private String senderNickname;
    private String content;
    private MessageType type;
    private LocalDateTime createdAt;

    public static MessageResponse from(Message message) {
        return new MessageResponse(
                message.getId(),
                message.getMessageKey(),
                message.getChatRoom().getId(),
                message.getSender().getId(),
                message.getSender().getNickname(),
                message.getContent(),
                message.getType(),
                message.getCreatedAt()
        );
    }
}
