package com.realtime.chat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
public class CreateDirectRoomRequest {

    @NotNull(message = "상대방 ID는 필수입니다.")
    private Long targetUserId;
}
