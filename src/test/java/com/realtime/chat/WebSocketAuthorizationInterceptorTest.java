package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.realtime.chat.config.WebSocketAuthorizationInterceptor;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthorizationInterceptorTest {

  @Mock private ChatRoomMemberRepository chatRoomMemberRepository;

  @Test
  @DisplayName("채팅방 멤버는 room topic을 구독할 수 있다")
  void memberCanSubscribeRoomTopic() {
    WebSocketAuthorizationInterceptor interceptor =
        new WebSocketAuthorizationInterceptor(chatRoomMemberRepository);
    Message<byte[]> message = subscribeMessage(10L, "/topic/room.20");
    given(chatRoomMemberRepository.existsByChatRoomIdAndUserId(20L, 10L)).willReturn(true);

    Message<?> result = interceptor.preSend(message, null);

    assertThat(result).isSameAs(message);
  }

  @Test
  @DisplayName("채팅방 멤버가 아니면 room topic 구독을 거부한다")
  void nonMemberCannotSubscribeRoomTopic() {
    WebSocketAuthorizationInterceptor interceptor =
        new WebSocketAuthorizationInterceptor(chatRoomMemberRepository);
    Message<byte[]> message = subscribeMessage(10L, "/topic/room.20");
    given(chatRoomMemberRepository.existsByChatRoomIdAndUserId(20L, 10L)).willReturn(false);

    assertThatThrownBy(() -> interceptor.preSend(message, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("채팅방을 구독할 권한이 없습니다");
  }

  @Test
  @DisplayName("room topic이 아닌 구독은 그대로 통과시킨다")
  void nonRoomTopicPassesThrough() {
    WebSocketAuthorizationInterceptor interceptor =
        new WebSocketAuthorizationInterceptor(chatRoomMemberRepository);
    Message<byte[]> message = subscribeMessage(10L, "/topic/presence");

    Message<?> result = interceptor.preSend(message, null);

    assertThat(result).isSameAs(message);
  }

  @Test
  @DisplayName("room topic처럼 보이지만 형식이 잘못된 destination은 거부한다")
  void malformedRoomTopicIsRejected() {
    WebSocketAuthorizationInterceptor interceptor =
        new WebSocketAuthorizationInterceptor(chatRoomMemberRepository);

    assertThatThrownBy(() -> interceptor.preSend(subscribeMessage(10L, "/topic/room"), null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("잘못된 채팅방 구독 경로입니다");
    assertThatThrownBy(() -> interceptor.preSend(subscribeMessage(10L, "/topic/room."), null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("잘못된 채팅방 구독 경로입니다");
    assertThatThrownBy(() -> interceptor.preSend(subscribeMessage(10L, "/topic/room.bad"), null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("잘못된 채팅방 구독 경로입니다");
    assertThatThrownBy(
            () -> interceptor.preSend(subscribeMessage(10L, "/topic/room.1.extra"), null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("잘못된 채팅방 구독 경로입니다");
    assertThatThrownBy(
            () ->
                interceptor.preSend(
                    subscribeMessage(10L, "/topic/room.999999999999999999999"), null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("잘못된 채팅방 구독 경로입니다");
  }

  private Message<byte[]> subscribeMessage(Long userId, String destination) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    accessor.setDestination(destination);
    accessor.setUser(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
