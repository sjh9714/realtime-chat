package com.realtime.chat.event;

import com.realtime.chat.domain.MessageType;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

// Kafka 메시지 스키마: 채팅 메시지
@Getter
@NoArgsConstructor
public class ChatMessageEvent {

  private UUID messageKey;
  private UUID clientMessageId;
  private Long roomId;
  private Long senderId;
  private String senderNickname;
  private String content;
  private MessageType type;
  private LocalDateTime timestamp;

  public ChatMessageEvent(
      UUID messageKey,
      Long roomId,
      Long senderId,
      String senderNickname,
      String content,
      MessageType type,
      LocalDateTime timestamp) {
    this(
        messageKey,
        roomId,
        senderId,
        senderNickname,
        content,
        type,
        UUID.randomUUID(),
        timestamp);
  }

  public ChatMessageEvent(
      UUID messageKey,
      Long roomId,
      Long senderId,
      String senderNickname,
      String content,
      MessageType type,
      UUID clientMessageId,
      LocalDateTime timestamp) {
    this.messageKey = messageKey;
    this.clientMessageId = clientMessageId != null ? clientMessageId : UUID.randomUUID();
    this.roomId = roomId;
    this.senderId = senderId;
    this.senderNickname = senderNickname;
    this.content = content;
    this.type = type;
    this.timestamp = timestamp;
  }

  public static ChatMessageEvent of(
      Long roomId, Long senderId, String senderNickname, String content, MessageType type) {
    return of(roomId, senderId, senderNickname, content, type, UUID.randomUUID());
  }

  public static ChatMessageEvent of(
      Long roomId,
      Long senderId,
      String senderNickname,
      String content,
      MessageType type,
      UUID clientMessageId) {
    return new ChatMessageEvent(
        UUID.randomUUID(),
        roomId,
        senderId,
        senderNickname,
        content,
        type,
        clientMessageId,
        LocalDateTime.now());
  }
}
