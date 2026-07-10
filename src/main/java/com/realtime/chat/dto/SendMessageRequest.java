package com.realtime.chat.dto;

import com.realtime.chat.domain.MessageType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class SendMessageRequest {

  private UUID clientMessageId;

  @NotNull(message = "채팅방 ID는 필수입니다.")
  private Long roomId;

  @NotBlank(message = "메시지 내용은 필수입니다.")
  @Size(max = 2000, message = "메시지는 2,000자 이하여야 합니다.")
  private String content;

  @NotNull(message = "메시지 타입은 필수입니다.")
  private MessageType type = MessageType.TEXT;
}
