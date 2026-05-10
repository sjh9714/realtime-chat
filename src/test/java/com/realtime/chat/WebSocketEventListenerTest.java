package com.realtime.chat;

import com.realtime.chat.config.WebSocketEventListener;
import com.realtime.chat.dto.PresenceEvent;
import com.realtime.chat.service.PresenceService;
import com.realtime.chat.service.RedisPubSubService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class WebSocketEventListenerTest {

    @Mock
    private PresenceService presenceService;

    @Mock
    private RedisPubSubService redisPubSubService;

    @Mock
    private SessionDisconnectEvent disconnectEvent;

    @Test
    @DisplayName("남은 session이 있으면 disconnect 시 offline 이벤트를 발행하지 않는다")
    void disconnectWithRemainingSessionDoesNotPublishOffline() {
        AtomicInteger gauge = new AtomicInteger(2);
        WebSocketEventListener listener = new WebSocketEventListener(presenceService, redisPubSubService, gauge);
        givenDisconnectEvent(10L, "s1");
        given(presenceService.setOffline(10L, "s1")).willReturn(false);

        listener.handleWebSocketDisconnect(disconnectEvent);

        verify(redisPubSubService, never()).publishPresence(org.mockito.ArgumentMatchers.any());
        assertThat(gauge.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("마지막 session이 끊기면 offline 이벤트를 발행한다")
    void disconnectLastSessionPublishesOffline() {
        AtomicInteger gauge = new AtomicInteger(1);
        WebSocketEventListener listener = new WebSocketEventListener(presenceService, redisPubSubService, gauge);
        givenDisconnectEvent(10L, "s1");
        given(presenceService.setOffline(10L, "s1")).willReturn(true);

        listener.handleWebSocketDisconnect(disconnectEvent);

        ArgumentCaptor<PresenceEvent> captor = ArgumentCaptor.forClass(PresenceEvent.class);
        verify(redisPubSubService).publishPresence(captor.capture());
        assertThat(captor.getValue().getUserId()).isEqualTo(10L);
        assertThat(captor.getValue().getStatus()).isEqualTo("OFFLINE");
        assertThat(gauge.get()).isZero();
    }

    private void givenDisconnectEvent(Long userId, String sessionId) {
        given(disconnectEvent.getUser())
                .willReturn(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
        given(disconnectEvent.getSessionId()).willReturn(sessionId);
    }
}
