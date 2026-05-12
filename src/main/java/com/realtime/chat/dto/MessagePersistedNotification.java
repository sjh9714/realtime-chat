package com.realtime.chat.dto;

import com.realtime.chat.domain.Message;
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
public class MessagePersistedNotification {

  private Long targetUserId;
  private UUID clientMessageId;
  private UUID messageKey;
  private Long messageId;
  private Long roomId;
  private LocalDateTime persistedAt;

  public static MessagePersistedNotification from(Message message) {
    return from(message, message.getSender().getId());
  }

  public static MessagePersistedNotification from(Message message, Long targetUserId) {
    return from(message, targetUserId, message.getChatRoom().getId());
  }

  public static MessagePersistedNotification from(Message message, Long targetUserId, Long roomId) {
    return new MessagePersistedNotification(
        targetUserId,
        message.getClientMessageId(),
        message.getMessageKey(),
        message.getId(),
        roomId,
        LocalDateTime.now());
  }
}
