package com.realtime.chat.dto;

import java.util.UUID;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MessagePublishErrorResponse {

  private UUID clientMessageId;
  private Long roomId;
  private MessagePublishStatus status;
  private String code;
  private String reason;
  private LocalDateTime failedAt;

  public static MessagePublishErrorResponse failed(
      UUID clientMessageId, Long roomId, String reason) {
    return new MessagePublishErrorResponse(
        clientMessageId,
        roomId,
        MessagePublishStatus.FAILED,
        "MESSAGE_PUBLISH_FAILED",
        reason,
        LocalDateTime.now());
  }
}
