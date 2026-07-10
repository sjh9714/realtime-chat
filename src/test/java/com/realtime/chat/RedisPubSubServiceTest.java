package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;
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
import com.realtime.chat.dto.MessagePublishStatus;
import com.realtime.chat.dto.MessageResponse;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.service.RedisPubSubService;
import com.realtime.chat.service.PersistenceFailureProbe;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;

@ExtendWith(MockitoExtension.class)
class RedisPubSubServiceTest {

  @Mock private StringRedisTemplate redisTemplate;

  @Mock private SimpMessagingTemplate messagingTemplate;

  @Mock private Counter messagesReceivedCounter;

  @Mock private Timer roomFanoutLatencyTimer;

  @Mock private ChatRoomMemberRepository chatRoomMemberRepository;
  @Mock private PersistenceFailureProbe failureProbe;

  private ObjectMapper objectMapper;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper().findAndRegisterModules();
  }

  @Test
  @DisplayName("채팅 메시지 Redis publish 실패는 Kafka consumer가 감지할 수 있도록 재전파한다")
  void chatMessagePublishFailureIsRethrown() {
    RedisPubSubService service = service();
    MessageResponse event = persistedMessage();
    String channel = RedisConfig.CHAT_ROOM_CHANNEL_PREFIX + event.getRoomId();
    given(redisTemplate.convertAndSend(eq(channel), anyString()))
        .willThrow(new RuntimeException("redis down"));

    assertThatThrownBy(() -> service.publishPersistedMessage(event))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Redis publish failed")
        .hasCauseInstanceOf(RuntimeException.class);
  }

  @Test
  @DisplayName("PERSISTED 알림은 user notification 채널로 발행한다")
  void publishPersistedNotification() throws Exception {
    RedisPubSubService service = service();
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
    RedisPubSubService service = service();
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

  @Test
  @DisplayName("PatternTopic 수신 channel이 pattern이어도 event roomId로 room topic에 전달한다")
  void onMessageUsesEventRoomIdWhenPatternTopicProvidesPatternChannel() throws Exception {
    RedisPubSubService service = service();
    MessageResponse event = persistedMessage();
    String message = objectMapper.writeValueAsString(event);

    service.onMessage(message, RedisConfig.CHAT_ROOM_PATTERN);

    ArgumentCaptor<Object> payloadCaptor = ArgumentCaptor.forClass(Object.class);
    verify(messagingTemplate).convertAndSend(eq("/topic/room.20"), payloadCaptor.capture());

    assertThat(payloadCaptor.getValue()).isInstanceOf(MessageResponse.class);
    MessageResponse received = (MessageResponse) payloadCaptor.getValue();
    assertThat(received.getRoomId()).isEqualTo(event.getRoomId());
    assertThat(received.getMessageKey()).isEqualTo(event.getMessageKey());
    assertThat(received.getId()).isEqualTo(100L);
    assertThat(received.getClientMessageId()).isEqualTo(event.getClientMessageId());
  }

  @Test
  @DisplayName("room topic 브로드캐스트 성공 시 received counter와 fan-out latency를 기록한다")
  void onMessageRecordsReceivedCounterAndFanoutLatency() throws Exception {
    RedisPubSubService service = service();
    MessageResponse event = persistedMessage();
    String message = objectMapper.writeValueAsString(event);

    service.onMessage(message, RedisConfig.CHAT_ROOM_PATTERN);

    verify(messagesReceivedCounter).increment();
    verify(roomFanoutLatencyTimer).record(org.mockito.ArgumentMatchers.any(java.time.Duration.class));
  }

  private RedisPubSubService service() {
    return new RedisPubSubService(
        redisTemplate,
        messagingTemplate,
        objectMapper,
        chatRoomMemberRepository,
        failureProbe,
        messagesReceivedCounter,
        roomFanoutLatencyTimer);
  }

  private MessageResponse persistedMessage() {
    return new MessageResponse(
        100L,
        UUID.randomUUID(),
        UUID.randomUUID(),
        20L,
        10L,
        "sender",
        "hello",
        MessageType.TEXT,
        MessagePublishStatus.PERSISTED,
        LocalDateTime.now());
  }
}
