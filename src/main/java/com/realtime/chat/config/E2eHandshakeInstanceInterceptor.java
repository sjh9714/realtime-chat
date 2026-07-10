package com.realtime.chat.config;

import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Component
@Profile("e2e & !prod")
public class E2eHandshakeInstanceInterceptor implements HandshakeInterceptor {

  public static final String INSTANCE_HEADER = "x-app-instance";

  private final String instanceId;

  public E2eHandshakeInstanceInterceptor(
      @Value("${chat.e2e.instance-id}") String instanceId) {
    this.instanceId = instanceId;
  }

  @Override
  public boolean beforeHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Map<String, Object> attributes) {
    response.getHeaders().set(INSTANCE_HEADER, instanceId);
    return true;
  }

  @Override
  public void afterHandshake(
      ServerHttpRequest request,
      ServerHttpResponse response,
      WebSocketHandler wsHandler,
      Exception exception) {}
}
