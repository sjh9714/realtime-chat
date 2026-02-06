package com.realtime.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

@Getter
@NoArgsConstructor
public class CreateGroupRoomRequest {

    @NotBlank(message = "채팅방 이름은 필수입니다.")
    @Size(max = 100, message = "채팅방 이름은 100자 이하여야 합니다.")
    private String name;

    // 초기 멤버 (선택, 생성자는 자동 포함)
    private List<Long> memberIds;
}
