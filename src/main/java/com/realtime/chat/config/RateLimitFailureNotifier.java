package com.realtime.chat.config;

import java.security.Principal;
import org.springframework.messaging.Message;

public interface RateLimitFailureNotifier {

  void notifyFailure(
      Message<?> message, Principal user, String code, String userFacingMessage);
}
