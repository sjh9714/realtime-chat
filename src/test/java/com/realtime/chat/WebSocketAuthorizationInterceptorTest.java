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
  @DisplayName("전역 presence topic은 사용자 상태 노출을 막기 위해 거부한다")
  void globalPresenceTopicIsRejected() {
    WebSocketAuthorizationInterceptor interceptor =
        new WebSocketAuthorizationInterceptor(chatRoomMemberRepository);
    Message<byte[]> message = subscribeMessage(10L, "/topic/presence");

    assertThatThrownBy(() -> interceptor.preSend(message, null))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("전체 사용자 presence 구독은 허용되지 않습니다");
  }

  @Test
  @DisplayName("채팅방 멤버만 해당 room presence topic을 구독할 수 있다")
  void roomPresenceSubscriptionRequiresMembership() {
    WebSocketAuthorizationInterceptor interceptor =
        new WebSocketAuthorizationInterceptor(chatRoomMemberRepository);
    Message<byte[]> message = subscribeMessage(10L, "/topic/room.20.presence");
    given(chatRoomMemberRepository.existsByChatRoomIdAndUserId(20L, 10L)).willReturn(true);

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

  @Test
  @DisplayName("클라이언트 SEND는 chat과 heartbeat application destination만 허용한다")
  void onlyApplicationSendDestinationsAreAllowed() {
    WebSocketAuthorizationInterceptor interceptor =
        new WebSocketAuthorizationInterceptor(chatRoomMemberRepository);

    assertThat(interceptor.preSend(sendMessage(10L, "/app/chat.send"), null)).isNotNull();
    assertThat(interceptor.preSend(sendMessage(10L, "/app/presence.heartbeat"), null)).isNotNull();
  }

  @Test
  @DisplayName("broker와 임의 application destination으로 직접 SEND할 수 없다")
  void directBrokerSendIsRejected() {
    WebSocketAuthorizationInterceptor interceptor =
        new WebSocketAuthorizationInterceptor(chatRoomMemberRepository);

    for (String destination :
        List.of(
            "/topic/room.20",
            "/queue/messages/ack",
            "/user/queue/messages/persisted",
            "/app/admin",
            "/unknown")) {
      assertThatThrownBy(() -> interceptor.preSend(sendMessage(10L, destination), null))
          .isInstanceOf(AccessDeniedException.class)
          .hasMessageContaining("허용되지 않은 메시지 전송 경로입니다");
    }
  }

  private Message<byte[]> subscribeMessage(Long userId, String destination) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
    accessor.setDestination(destination);
    accessor.setUser(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }

  private Message<byte[]> sendMessage(Long userId, String destination) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
    accessor.setDestination(destination);
    accessor.setUser(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
