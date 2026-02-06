package com.realtime.chat;

import com.realtime.chat.common.BusinessException;
import com.realtime.chat.domain.ChatRoom;
import com.realtime.chat.domain.RoomType;
import com.realtime.chat.domain.User;
import com.realtime.chat.dto.ChatRoomResponse;
import com.realtime.chat.dto.CreateDirectRoomRequest;
import com.realtime.chat.dto.CreateGroupRoomRequest;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.ChatRoomRepository;
import com.realtime.chat.repository.UserRepository;
import com.realtime.chat.service.ChatRoomService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class ChatRoomServiceTest {

    @InjectMocks
    private ChatRoomService chatRoomService;

    @Mock
    private ChatRoomRepository chatRoomRepository;

    @Mock
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @Mock
    private UserRepository userRepository;

    private User createUser(Long id, String email, String nickname) {
        User user = new User(email, "encoded", nickname);
        ReflectionTestUtils.setField(user, "id", id);
        return user;
    }

    @Test
    @DisplayName("1:1 채팅방 생성 성공")
    void createDirectRoomSuccess() {
        User user1 = createUser(1L, "user1@test.com", "유저1");
        User user2 = createUser(2L, "user2@test.com", "유저2");

        CreateDirectRoomRequest request = new CreateDirectRoomRequest();
        ReflectionTestUtils.setField(request, "targetUserId", 2L);

        given(userRepository.findById(1L)).willReturn(Optional.of(user1));
        given(userRepository.findById(2L)).willReturn(Optional.of(user2));
        given(chatRoomRepository.findDirectRoomByUsers(eq(RoomType.DIRECT), eq(1L), eq(2L)))
                .willReturn(Optional.empty());
        given(chatRoomRepository.save(any(ChatRoom.class))).willAnswer(invocation -> {
            ChatRoom room = invocation.getArgument(0);
            ReflectionTestUtils.setField(room, "id", 1L);
            return room;
        });

        ChatRoomResponse response = chatRoomService.createDirectRoom(1L, request);

        assertThat(response.getType()).isEqualTo(RoomType.DIRECT);
        assertThat(response.getMembers()).hasSize(2);
    }

    @Test
    @DisplayName("자기 자신과의 1:1 방 생성 실패")
    void createDirectRoomWithSelf() {
        CreateDirectRoomRequest request = new CreateDirectRoomRequest();
        ReflectionTestUtils.setField(request, "targetUserId", 1L);

        assertThatThrownBy(() -> chatRoomService.createDirectRoom(1L, request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("자기 자신과의 채팅방은 생성할 수 없습니다.");
    }

    @Test
    @DisplayName("그룹 채팅방 생성 성공")
    void createGroupRoomSuccess() {
        User user1 = createUser(1L, "user1@test.com", "유저1");

        CreateGroupRoomRequest request = new CreateGroupRoomRequest();
        ReflectionTestUtils.setField(request, "name", "테스트 그룹");
        ReflectionTestUtils.setField(request, "memberIds", null);

        given(userRepository.findById(1L)).willReturn(Optional.of(user1));
        given(chatRoomRepository.save(any(ChatRoom.class))).willAnswer(invocation -> {
            ChatRoom room = invocation.getArgument(0);
            ReflectionTestUtils.setField(room, "id", 1L);
            return room;
        });

        ChatRoomResponse response = chatRoomService.createGroupRoom(1L, request);

        assertThat(response.getName()).isEqualTo("테스트 그룹");
        assertThat(response.getType()).isEqualTo(RoomType.GROUP);
        assertThat(response.getMembers()).hasSize(1);
    }
}
