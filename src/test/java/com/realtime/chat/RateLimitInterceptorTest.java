package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.realtime.chat.config.RateLimitInterceptor;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class RateLimitInterceptorTest {

  @Test
  @DisplayName("SEND 명령은 초당 제한을 초과하면 거부한다")
  void sendCommandIsRateLimited() {
    RateLimitInterceptor interceptor = new RateLimitInterceptor(1);
    Message<byte[]> first = stompMessage(StompCommand.SEND);
    Message<byte[]> second = stompMessage(StompCommand.SEND);

    assertThat(interceptor.preSend(first, null)).isSameAs(first);
    assertThatThrownBy(() -> interceptor.preSend(second, null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("메시지 전송 속도 제한을 초과했습니다");
  }

  @Test
  @DisplayName("SUBSCRIBE 명령에는 SEND rate limit을 적용하지 않는다")
  void subscribeCommandIsNotRateLimited() {
    RateLimitInterceptor interceptor = new RateLimitInterceptor(1);
    Message<byte[]> first = stompMessage(StompCommand.SUBSCRIBE);
    Message<byte[]> second = stompMessage(StompCommand.SUBSCRIBE);

    assertThat(interceptor.preSend(first, null)).isSameAs(first);
    assertThat(interceptor.preSend(second, null)).isSameAs(second);
  }

  private Message<byte[]> stompMessage(StompCommand command) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
    accessor.setUser(new UsernamePasswordAuthenticationToken(10L, null, List.of()));
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
