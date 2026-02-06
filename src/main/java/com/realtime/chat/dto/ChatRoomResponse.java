package com.realtime.chat.dto;

import com.realtime.chat.domain.ChatRoom;
import com.realtime.chat.domain.ChatRoomMember;
import com.realtime.chat.domain.RoomType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@AllArgsConstructor
public class ChatRoomResponse {

    private Long id;
    private String name;
    private RoomType type;
    private Long createdBy;
    private LocalDateTime createdAt;
    private List<MemberInfo> members;

    @Getter
    @AllArgsConstructor
    public static class MemberInfo {
        private Long userId;
        private String nickname;
        private int unreadCount;
    }

    public static ChatRoomResponse from(ChatRoom room) {
        List<MemberInfo> memberInfos = room.getMembers().stream()
                .map(m -> new MemberInfo(
                        m.getUser().getId(),
                        m.getUser().getNickname(),
                        m.getUnreadCount()))
                .toList();

        return new ChatRoomResponse(
                room.getId(),
                room.getName(),
                room.getType(),
                room.getCreatedBy().getId(),
                room.getCreatedAt(),
                memberInfos
        );
    }
}
