package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtime.chat.config.RedisConfig;
import com.realtime.chat.domain.MessageType;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.service.RedisPubSubService;
import java.time.LocalDateTime;
import java.util.UUID;
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

  @Test
  @DisplayName("채팅 메시지 Redis publish 실패는 Kafka consumer가 감지할 수 있도록 재전파한다")
  void chatMessagePublishFailureIsRethrown() {
    RedisPubSubService service =
        new RedisPubSubService(
            redisTemplate, messagingTemplate, new ObjectMapper().findAndRegisterModules());
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
}
