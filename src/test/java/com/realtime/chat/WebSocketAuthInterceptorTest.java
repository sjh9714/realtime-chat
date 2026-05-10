package com.realtime.chat;

import com.realtime.chat.common.JwtTokenProvider;
import com.realtime.chat.config.WebSocketAuthInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {

    @Mock
    private JwtTokenProvider jwtTokenProvider;

    @Test
    @DisplayName("CONNECT Authorization Bearer 토큰을 검증하고 Principal에 userId를 바인딩한다")
    void connectWithValidTokenBindsPrincipal() {
        WebSocketAuthInterceptor interceptor = new WebSocketAuthInterceptor(jwtTokenProvider);
        Message<byte[]> message = connectMessage("Bearer token");
        given(jwtTokenProvider.validateToken("token")).willReturn(true);
        given(jwtTokenProvider.getUserId("token")).willReturn(10L);

        Message<?> result = interceptor.preSend(message, null);
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(result);

        assertThat(accessor.getUser()).isNotNull();
        assertThat(accessor.getUser().getName()).isEqualTo("10");
    }

    @Test
    @DisplayName("CONNECT Authorization 헤더가 없으면 연결을 거부한다")
    void connectWithoutTokenIsRejected() {
        WebSocketAuthInterceptor interceptor = new WebSocketAuthInterceptor(jwtTokenProvider);

        assertThatThrownBy(() -> interceptor.preSend(connectMessage(null), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("인증 토큰이 필요합니다");
    }

    private Message<byte[]> connectMessage(String authorization) {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        if (authorization != null) {
            accessor.addNativeHeader("Authorization", authorization);
        }
        accessor.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }
}
