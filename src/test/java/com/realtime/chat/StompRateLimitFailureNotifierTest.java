package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtime.chat.config.StompRateLimitFailureNotifier;
import com.realtime.chat.dto.StompErrorResponse;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.support.MessageBuilder;

class StompRateLimitFailureNotifierTest {

  @Test
  @DisplayName("rate limit 오류는 clientMessageId와 roomId를 보존한 구조화 payload다")
  void keepsOptimisticMessageCorrelation() {
    SimpMessagingTemplate messagingTemplate = mock(SimpMessagingTemplate.class);
    StompRateLimitFailureNotifier notifier =
        new StompRateLimitFailureNotifier(new ObjectMapper(), messagingTemplate);
    UUID clientMessageId = UUID.randomUUID();
    Message<byte[]> message =
        MessageBuilder.withPayload(
                ("{\"clientMessageId\":\""
                        + clientMessageId
                        + "\",\"roomId\":42,\"content\":\"hello\",\"type\":\"TEXT\"}")
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8))
            .build();
    Principal principal = () -> "7";

    notifier.notifyFailure(message, principal, "RATE_LIMITED", "slow down");

    ArgumentCaptor<StompErrorResponse> response = ArgumentCaptor.forClass(StompErrorResponse.class);
    verify(messagingTemplate)
        .convertAndSendToUser(eq("7"), eq("/queue/errors"), response.capture());
    assertThat(response.getValue().getClientMessageId()).isEqualTo(clientMessageId);
    assertThat(response.getValue().getRoomId()).isEqualTo(42L);
    assertThat(response.getValue().getCode()).isEqualTo("RATE_LIMITED");
  }
}
