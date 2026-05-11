package com.realtime.chat.dto;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MessagePublishAckResponse {

  private UUID clientMessageId;
  private Long roomId;
  private MessagePublishStatus status;
  private LocalDateTime acceptedAt;

  public static MessagePublishAckResponse accepted(UUID clientMessageId, Long roomId) {
    return new MessagePublishAckResponse(
        clientMessageId, roomId, MessagePublishStatus.ACCEPTED, LocalDateTime.now());
  }
}
