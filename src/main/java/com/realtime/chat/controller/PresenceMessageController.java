package com.realtime.chat.controller;

import com.realtime.chat.service.PresenceService;
import java.security.Principal;
import lombok.RequiredArgsConstructor;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;

@Controller
@RequiredArgsConstructor
public class PresenceMessageController {

  private final PresenceService presenceService;

  @MessageMapping("/presence.heartbeat")
  public void heartbeat(Principal principal, @Header("simpSessionId") String sessionId) {
    if (principal == null || sessionId == null) {
      return;
    }
    presenceService.refreshSession(Long.parseLong(principal.getName()), sessionId);
  }
}
