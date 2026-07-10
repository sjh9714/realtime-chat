package com.realtime.chat.dto;

import com.realtime.chat.domain.ChatRoom;
import com.realtime.chat.domain.ChatRoomMember;
import com.realtime.chat.domain.Message;
import com.realtime.chat.domain.RoomType;
import java.time.LocalDateTime;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ChatRoomListResponse {

  private Long id;
  private String name;
  private String displayName;
  private RoomType type;
  private int memberCount;
  private int unreadCount;
  private Long lastMessageId;
  private String lastMessageContent;
  private String lastMessageSenderNickname;
  private LocalDateTime lastMessageAt;
  private LocalDateTime createdAt;

  // JPQL 프로젝션용 생성자 (COUNT 결과가 Long이므로 변환)
  public ChatRoomListResponse(
      Long id,
      String name,
      RoomType type,
      Long memberCount,
      int unreadCount,
      LocalDateTime createdAt) {
    this(
        id,
        name,
        name,
        type,
        memberCount.intValue(),
        unreadCount,
        null,
        null,
        null,
        null,
        createdAt);
  }

  public ChatRoomListResponse enrich(String resolvedDisplayName, Message lastMessage) {
    return new ChatRoomListResponse(
        id,
        name,
        resolvedDisplayName,
        type,
        memberCount,
        unreadCount,
        lastMessage != null ? lastMessage.getId() : null,
        lastMessage != null ? lastMessage.getContent() : null,
        lastMessage != null ? lastMessage.getSender().getNickname() : null,
        lastMessage != null ? lastMessage.getCreatedAt() : null,
        createdAt);
  }

  public static ChatRoomListResponse from(ChatRoom room, Long userId) {
    int unreadCount =
        room.getMembers().stream()
            .filter(m -> m.getUser().getId().equals(userId))
            .findFirst()
            .map(ChatRoomMember::getUnreadCount)
            .orElse(0);

    return new ChatRoomListResponse(
        room.getId(),
        room.getName(),
        room.getName(),
        room.getType(),
        room.getMembers().size(),
        unreadCount,
        null,
        null,
        null,
        null,
        room.getCreatedAt());
  }
}
