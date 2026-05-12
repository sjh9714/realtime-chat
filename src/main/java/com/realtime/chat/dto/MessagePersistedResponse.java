package com.realtime.chat.dto;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class MessagePersistedResponse {

  private UUID clientMessageId;
  private UUID messageKey;
  private Long messageId;
  private Long roomId;
  private MessagePublishStatus status;
  private LocalDateTime persistedAt;

  public static MessagePersistedResponse from(MessagePersistedNotification notification) {
    return new MessagePersistedResponse(
        notification.getClientMessageId(),
        notification.getMessageKey(),
        notification.getMessageId(),
        notification.getRoomId(),
        MessagePublishStatus.PERSISTED,
        notification.getPersistedAt());
  }
}
