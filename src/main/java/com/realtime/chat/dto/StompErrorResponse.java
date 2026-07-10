package com.realtime.chat.dto;

import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class StompErrorResponse {

  private String code;
  private String message;
  private UUID clientMessageId;
  private Long roomId;
  private LocalDateTime timestamp;

  public static StompErrorResponse of(
      String code, String message, UUID clientMessageId, Long roomId) {
    return new StompErrorResponse(
        code, message, clientMessageId, roomId, LocalDateTime.now());
  }
}
