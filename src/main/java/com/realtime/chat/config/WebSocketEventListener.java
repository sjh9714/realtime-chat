package com.realtime.chat.config;

import com.realtime.chat.dto.PresenceEvent;
import com.realtime.chat.service.PresenceService;
import com.realtime.chat.service.RedisPubSubService;
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

    @EventListener
    public void handleWebSocketConnect(SessionConnectEvent event) {
        Long userId = extractUserId(event.getMessage().getHeaders().get("simpUser"));
        if (userId != null) {
            presenceService.setOnline(userId);
            redisPubSubService.publishPresence(PresenceEvent.online(userId));
            log.info("WebSocket 연결: userId={}, sessionId={}", userId,
                    StompHeaderAccessor.wrap(event.getMessage()).getSessionId());
        }
    }

    @EventListener
    public void handleWebSocketDisconnect(SessionDisconnectEvent event) {
        Long userId = extractUserId(event.getUser());
        if (userId != null) {
            presenceService.setOffline(userId);
            redisPubSubService.publishPresence(PresenceEvent.offline(userId));
            log.info("WebSocket 해제: userId={}, sessionId={}", userId, event.getSessionId());
        }
    }

    private Long extractUserId(Object principal) {
        if (principal instanceof UsernamePasswordAuthenticationToken auth) {
            return (Long) auth.getPrincipal();
        }
        return null;
    }
}
