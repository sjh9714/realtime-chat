package com.realtime.chat;

import com.realtime.chat.controller.PresenceMessageController;
import com.realtime.chat.service.PresenceService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.security.Principal;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class PresenceMessageControllerTest {

    @Test
    @DisplayName("presence heartbeat는 현재 WebSocket session TTL을 갱신한다")
    void heartbeatRefreshesCurrentSession() {
        PresenceService presenceService = mock(PresenceService.class);
        PresenceMessageController controller = new PresenceMessageController(presenceService);

        controller.heartbeat(principal("10"), "s1");

        verify(presenceService).refreshSession(10L, "s1");
    }

    private Principal principal(String name) {
        return () -> name;
    }
}
