package com.realtime.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtime.chat.dto.SendMessageRequest;
import com.realtime.chat.dto.StompErrorResponse;
import java.security.Principal;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class StompRateLimitFailureNotifier implements RateLimitFailureNotifier {

  private final ObjectMapper objectMapper;
  private final SimpMessagingTemplate messagingTemplate;

  public StompRateLimitFailureNotifier(
      ObjectMapper objectMapper, @Lazy SimpMessagingTemplate messagingTemplate) {
    this.objectMapper = objectMapper;
    this.messagingTemplate = messagingTemplate;
  }

  @Override
  public void notifyFailure(
      Message<?> message, Principal user, String code, String userFacingMessage) {
    SendMessageRequest request = readRequest(message.getPayload());
    messagingTemplate.convertAndSendToUser(
        user.getName(),
        "/queue/errors",
        StompErrorResponse.of(
            code,
            userFacingMessage,
            request != null ? request.getClientMessageId() : null,
            request != null ? request.getRoomId() : null));
  }

  private SendMessageRequest readRequest(Object payload) {
    try {
      if (payload instanceof byte[] bytes) {
        return objectMapper.readValue(bytes, SendMessageRequest.class);
      }
      if (payload instanceof String value) {
        return objectMapper.readValue(value, SendMessageRequest.class);
      }
    } catch (Exception exception) {
      log.debug("rate limit correlation payload를 읽지 못했습니다", exception);
    }
    return null;
  }
}
