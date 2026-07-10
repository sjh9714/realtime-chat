package com.realtime.chat.dto;

import com.realtime.chat.domain.Message;
import com.realtime.chat.domain.MessageType;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class MessageResponse {

  private Long id;
  private UUID messageKey;
  private UUID clientMessageId;
  private Long roomId;
  private Long senderId;
  private String senderNickname;
  private String content;
  private MessageType type;
  private MessagePublishStatus status;
  private LocalDateTime createdAt;

  public static MessageResponse from(Message message) {
    return new MessageResponse(
        message.getId(),
        message.getMessageKey(),
        message.getClientMessageId(),
        message.getChatRoom().getId(),
        message.getSender().getId(),
        message.getSender().getNickname(),
        message.getContent(),
        message.getType(),
        MessagePublishStatus.PERSISTED,
        message.getCreatedAt());
  }
}
