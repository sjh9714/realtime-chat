package com.realtime.chat.dto;

import com.realtime.chat.domain.ChatRoom;
import com.realtime.chat.domain.ChatRoomMember;
import com.realtime.chat.domain.RoomType;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@AllArgsConstructor
public class ChatRoomListResponse {

    private Long id;
    private String name;
    private RoomType type;
    private int memberCount;
    private int unreadCount;
    private LocalDateTime createdAt;

    public static ChatRoomListResponse from(ChatRoom room, Long userId) {
        int unreadCount = room.getMembers().stream()
                .filter(m -> m.getUser().getId().equals(userId))
                .findFirst()
                .map(ChatRoomMember::getUnreadCount)
                .orElse(0);

        return new ChatRoomListResponse(
                room.getId(),
                room.getName(),
                room.getType(),
                room.getMembers().size(),
                unreadCount,
                room.getCreatedAt()
        );
    }
}
