package com.realtime.chat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class ReadReceiptRequest {

    @NotNull(message = "마지막으로 읽은 메시지 ID는 필수입니다.")
    private Long lastReadMessageId;
}
