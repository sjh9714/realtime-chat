package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.realtime.chat.config.E2eHandshakeInstanceInterceptor;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

class E2eHandshakeInstanceInterceptorTest {

  @Test
  void identifiesTheActualAppOnTheWebSocketUpgradeResponse() {
    E2eHandshakeInstanceInterceptor interceptor =
        new E2eHandshakeInstanceInterceptor("app-1");
    ServerHttpRequest request = mock(ServerHttpRequest.class);
    ServerHttpResponse response = mock(ServerHttpResponse.class);
    WebSocketHandler handler = mock(WebSocketHandler.class);
    HttpHeaders headers = new HttpHeaders();
    when(response.getHeaders()).thenReturn(headers);

    assertThat(interceptor.beforeHandshake(request, response, handler, new HashMap<>()))
        .isTrue();
    assertThat(headers.getFirst(E2eHandshakeInstanceInterceptor.INSTANCE_HEADER))
        .isEqualTo("app-1");
  }
}
