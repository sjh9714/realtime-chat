package com.realtime.chat.config;

import com.realtime.chat.dto.PresenceEvent;
import com.realtime.chat.service.PresenceService;
import com.realtime.chat.service.RedisPubSubService;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionConnectEvent;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

// WebSocket 연결/해제 이벤트 감지 → 온라인 상태 관리
@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketEventListener {

  private final PresenceService presenceService;
  private final RedisPubSubService redisPubSubService;
  private final AtomicInteger websocketSessionGauge;

  @EventListener
  public void handleWebSocketConnect(SessionConnectEvent event) {
    Long userId = extractUserId(event.getMessage().getHeaders().get("simpUser"));
    String sessionId = StompHeaderAccessor.wrap(event.getMessage()).getSessionId();
    if (userId != null && sessionId != null) {
      boolean becameOnline = presenceService.setOnline(userId, sessionId);
      if (becameOnline) {
        redisPubSubService.publishPresence(PresenceEvent.online(userId));
      }
      websocketSessionGauge.incrementAndGet();
      log.info("WebSocket 연결: userId={}, sessionId={}", userId, sessionId);
    }
  }

  @EventListener
  public void handleWebSocketDisconnect(SessionDisconnectEvent event) {
    Long userId = extractUserId(event.getUser());
    String sessionId = event.getSessionId();
    if (userId != null && sessionId != null) {
      boolean becameOffline = presenceService.setOffline(userId, sessionId);
      if (becameOffline) {
        redisPubSubService.publishPresence(PresenceEvent.offline(userId));
      }
      websocketSessionGauge.updateAndGet(count -> Math.max(0, count - 1));
      log.info("WebSocket 해제: userId={}, sessionId={}", userId, event.getSessionId());
    }
  }

  private Long extractUserId(Object principal) {
    if (principal instanceof UsernamePasswordAuthenticationToken auth) {
      Object userId = auth.getPrincipal();
      if (userId instanceof Long id) {
        return id;
      }
      return Long.parseLong(auth.getName());
    }
    return null;
  }
}
