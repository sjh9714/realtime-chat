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

  /**
   * 보낸 이가 봇인가. 화면이 이름 옆에 BOT 배지를 그리는 근거다.
   *
   * <p>Kafka 이벤트에 실어 나르지 않는다. 저장된 {@link Message}가 보낸 이를 들고 있으므로
   * 여기서 읽으면 된다 — 이벤트에 복사해 두면 그 값이 낡을 수 있다.
   */
  private boolean senderBot;

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
        message.getSender().isBot(),
        message.getContent(),
        message.getType(),
        MessagePublishStatus.PERSISTED,
        message.getCreatedAt());
  }
}
