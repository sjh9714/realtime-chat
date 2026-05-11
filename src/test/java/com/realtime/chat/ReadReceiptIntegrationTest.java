package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.realtime.chat.domain.ChatRoom;
import com.realtime.chat.domain.ChatRoomMember;
import com.realtime.chat.domain.Message;
import com.realtime.chat.domain.MessageType;
import com.realtime.chat.domain.RoomType;
import com.realtime.chat.domain.User;
import com.realtime.chat.event.ReadReceiptEvent;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.ChatRoomRepository;
import com.realtime.chat.repository.MessageRepository;
import com.realtime.chat.repository.UserRepository;
import com.realtime.chat.service.ReadReceiptService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

class ReadReceiptIntegrationTest extends BaseIntegrationTest {

  @Autowired private UserRepository userRepository;

  @Autowired private ChatRoomRepository chatRoomRepository;

  @Autowired private ChatRoomMemberRepository chatRoomMemberRepository;

  @Autowired private MessageRepository messageRepository;

  @Autowired private ReadReceiptService readReceiptService;

  @BeforeEach
  void setUp() {
    messageRepository.deleteAll();
    chatRoomMemberRepository.deleteAll();
    chatRoomRepository.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  @DisplayName("내가 보낸 메시지는 내 unreadCount에 포함되지 않는다")
  void ownMessagesAreExcludedFromUnreadCount() {
    User user1 = userRepository.save(new User("user1@test.com", "encoded", "유저1"));
    User user2 = userRepository.save(new User("user2@test.com", "encoded", "유저2"));
    ChatRoom room = createRoomWithMembers(user1, user2);
    messageRepository.saveAndFlush(
        new Message(UUID.randomUUID(), room, user1, "내 메시지", MessageType.TEXT));
    messageRepository.saveAndFlush(
        new Message(UUID.randomUUID(), room, user2, "상대 메시지", MessageType.TEXT));

    readReceiptService.processReadReceipt(
        new ReadReceiptEvent(room.getId(), user1.getId(), 0L, LocalDateTime.now()));

    ChatRoomMember member =
        chatRoomMemberRepository.findByChatRoomIdAndUserId(room.getId(), user1.getId()).get();
    assertThat(member.getUnreadCount()).isEqualTo(1);
  }

  @Test
  @DisplayName("사용자가 방에 참여하기 전 메시지는 unreadCount에 포함되지 않는다")
  void messagesBeforeJoinedAtAreExcludedFromUnreadCount() {
    User owner = userRepository.save(new User("owner@test.com", "encoded", "방장"));
    User lateMember = userRepository.save(new User("late@test.com", "encoded", "늦은멤버"));
    ChatRoom room = new ChatRoom("그룹", RoomType.GROUP, owner);
    room.addMember(owner);
    chatRoomRepository.saveAndFlush(room);

    messageRepository.saveAndFlush(
        new Message(UUID.randomUUID(), room, owner, "참여 전 메시지", MessageType.TEXT));
    room.addMember(lateMember);
    chatRoomRepository.saveAndFlush(room);
    messageRepository.saveAndFlush(
        new Message(UUID.randomUUID(), room, owner, "참여 후 메시지", MessageType.TEXT));

    readReceiptService.processReadReceipt(
        new ReadReceiptEvent(room.getId(), lateMember.getId(), 0L, LocalDateTime.now()));

    ChatRoomMember member =
        chatRoomMemberRepository.findByChatRoomIdAndUserId(room.getId(), lateMember.getId()).get();
    assertThat(member.getUnreadCount()).isEqualTo(1);
  }

  private ChatRoom createRoomWithMembers(User user1, User user2) {
    ChatRoom room = new ChatRoom(null, RoomType.DIRECT, user1);
    room.addMember(user1);
    room.addMember(user2);
    return chatRoomRepository.saveAndFlush(room);
  }
}
