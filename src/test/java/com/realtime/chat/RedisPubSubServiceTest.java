package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtime.chat.config.RedisConfig;
import com.realtime.chat.domain.MessageType;
import com.realtime.chat.dto.MessagePersistedNotification;
import com.realtime.chat.dto.MessagePersistedResponse;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.service.RedisPubSubService;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class RedisPubSubServiceTest {

  @Mock private StringRedisTemplate redisTemplate;

  @Mock private SimpMessagingTemplate messagingTemplate;

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().findAndRegisterModules();
  }

  @Test
  @DisplayName("채팅 메시지 Redis publish 실패는 Kafka consumer가 감지할 수 있도록 재전파한다")
  void chatMessagePublishFailureIsRethrown() {
    RedisPubSubService service = new RedisPubSubService(redisTemplate, messagingTemplate, objectMapper);
    ChatMessageEvent event =
        new ChatMessageEvent(
            UUID.randomUUID(), 20L, 10L, "sender", "hello", MessageType.TEXT, LocalDateTime.now());
    String channel = RedisConfig.CHAT_ROOM_CHANNEL_PREFIX + event.getRoomId();
    given(redisTemplate.convertAndSend(eq(channel), anyString()))
        .willThrow(new RuntimeException("redis down"));

    assertThatThrownBy(() -> service.publish(event))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Redis publish failed")
        .hasCauseInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName("PERSISTED 알림은 user notification 채널로 발행한다")
  void publishPersistedNotification() throws Exception {
    RedisPubSubService service = new RedisPubSubService(redisTemplate, messagingTemplate, objectMapper);
    MessagePersistedNotification notification =
        new MessagePersistedNotification(
            10L,
            UUID.randomUUID(),
            UUID.randomUUID(),
            100L,
            20L,
            LocalDateTime.now());

    service.publishPersisted(notification);

    String serialized = objectMapper.writeValueAsString(notification);
    verify(redisTemplate).convertAndSend(RedisConfig.USER_NOTIFICATION_CHANNEL, serialized);
  }

  @Test
  @DisplayName("PERSISTED 알림 수신 시 target user의 persisted queue로 전달한다")
  void onPersistedNotificationSendsToUserDestination() throws Exception {
    RedisPubSubService service = new RedisPubSubService(redisTemplate, messagingTemplate, objectMapper);
    MessagePersistedNotification notification =
        new MessagePersistedNotification(
            10L,
            UUID.randomUUID(),
            UUID.randomUUID(),
            100L,
            20L,
            LocalDateTime.now());
    String message = objectMapper.writeValueAsString(notification);

    service.onUserNotificationMessage(message, RedisConfig.USER_NOTIFICATION_CHANNEL);

    verify(messagingTemplate)
        .convertAndSendToUser(
            eq("10"), eq("/queue/messages/persisted"), eq(MessagePersistedResponse.from(notification)));
  }
}
