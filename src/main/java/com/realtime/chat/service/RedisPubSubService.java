package com.realtime.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtime.chat.config.RedisConfig;
import com.realtime.chat.dto.MessagePersistedNotification;
import com.realtime.chat.dto.MessagePersistedResponse;
import com.realtime.chat.dto.MessageResponse;
import com.realtime.chat.dto.PresenceEvent;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

// Redis Pub/Sub를 통한 서버 간 메시지 브로드캐스트
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisPubSubService {

  private final StringRedisTemplate redisTemplate;
  private final SimpMessagingTemplate messagingTemplate;
  private final ObjectMapper objectMapper;
  private final ChatRoomMemberRepository chatRoomMemberRepository;
  private final PersistenceFailureProbe failureProbe;
  @Qualifier("messagesReceivedCounter")
  private final Counter messagesReceivedCounter;
  @Qualifier("roomFanoutLatencyTimer")
  private final Timer roomFanoutLatencyTimer;

  // DB commit이 끝난 메시지만 Redis 채널에 발행한다.
  public void publishPersistedMessage(MessageResponse messageResponse) {
    try {
      failureProbe.beforeRedisPublish(messageResponse);
      String channel = RedisConfig.CHAT_ROOM_CHANNEL_PREFIX + messageResponse.getRoomId();
      String message = objectMapper.writeValueAsString(messageResponse);
      redisTemplate.convertAndSend(channel, message);
      log.debug(
          "Redis PERSISTED 메시지 발행: channel={}, messageId={}, clientMessageId={}",
          channel,
          messageResponse.getId(),
          messageResponse.getClientMessageId());
    } catch (Exception e) {
      log.error(
          "Redis PERSISTED 메시지 발행 실패: messageId={}, clientMessageId={}",
          messageResponse.getId(),
          messageResponse.getClientMessageId(),
          e);
      throw new IllegalStateException("Redis publish failed", e);
    }
  }

  // Redis 채널에 Presence 이벤트 발행 (서버 간 상태 공유)
  public void publishPresence(PresenceEvent event) {
    try {
      String message = objectMapper.writeValueAsString(event);
      redisTemplate.convertAndSend(RedisConfig.PRESENCE_CHANNEL, message);
      log.debug("Presence 발행: userId={}, status={}", event.getUserId(), event.getStatus());
    } catch (Exception e) {
      log.error("Presence 발행 실패: userId={}", event.getUserId(), e);
    }
  }

  // Redis 채널에 DB 저장 완료 알림 발행 (서버 간 user destination 라우팅)
  public void publishPersisted(MessagePersistedNotification notification) {
    try {
      String message = objectMapper.writeValueAsString(notification);
      redisTemplate.convertAndSend(RedisConfig.USER_NOTIFICATION_CHANNEL, message);
      log.debug(
          "PERSISTED 알림 발행: userId={}, messageKey={}",
          notification.getTargetUserId(),
          notification.getMessageKey());
    } catch (Exception e) {
      log.error(
          "PERSISTED 알림 발행 실패: userId={}, messageKey={}",
          notification.getTargetUserId(),
          notification.getMessageKey(),
          e);
      throw new IllegalStateException("Redis persisted notification publish failed", e);
    }
  }

  // Redis 구독 Presence 이벤트 수신 → 사용자가 속한 room별 topic으로만 fan-out
  public void onPresenceMessage(String message, String channel) {
    try {
      PresenceEvent event = objectMapper.readValue(message, PresenceEvent.class);
      chatRoomMemberRepository
          .findRoomIdsByUserId(event.getUserId())
          .forEach(
              roomId ->
                  messagingTemplate.convertAndSend(
                      "/topic/room." + roomId + ".presence", event));
      log.debug(
          "Presence room fan-out: userId={}, status={}", event.getUserId(), event.getStatus());
    } catch (Exception e) {
      log.error("Presence 브로드캐스트 실패: channel={}", channel, e);
    }
  }

  // Redis 구독 user notification 수신 → 해당 user destination으로 전달
  public void onUserNotificationMessage(String message, String channel) {
    try {
      MessagePersistedNotification notification =
          objectMapper.readValue(message, MessagePersistedNotification.class);
      messagingTemplate.convertAndSendToUser(
          String.valueOf(notification.getTargetUserId()),
          "/queue/messages/persisted",
          MessagePersistedResponse.from(notification));
      log.debug(
          "PERSISTED 알림 전달: userId={}, messageKey={}",
          notification.getTargetUserId(),
          notification.getMessageKey());
    } catch (Exception e) {
      log.error("PERSISTED 알림 전달 실패: channel={}", channel, e);
    }
  }

  // Redis 구독 메시지 수신 → STOMP로 WebSocket 클라이언트에게 브로드캐스트
  public void onMessage(String message, String channel) {
    Instant startedAt = Instant.now();
    try {
      MessageResponse event = objectMapper.readValue(message, MessageResponse.class);
      String destination = "/topic/room." + event.getRoomId();

      messagingTemplate.convertAndSend(destination, event);
      messagesReceivedCounter.increment();
      roomFanoutLatencyTimer.record(Duration.between(startedAt, Instant.now()));
      log.debug(
          "WebSocket PERSISTED 브로드캐스트: destination={}, messageId={}, clientMessageId={}",
          destination,
          event.getId(),
          event.getClientMessageId());
    } catch (Exception e) {
      log.error("WebSocket 브로드캐스트 실패: channel={}", channel, e);
    }
  }
}
