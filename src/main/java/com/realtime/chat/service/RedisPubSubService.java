package com.realtime.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtime.chat.config.RedisConfig;
import com.realtime.chat.dto.MessagePersistedNotification;
import com.realtime.chat.dto.MessagePersistedResponse;
import com.realtime.chat.dto.PresenceEvent;
import com.realtime.chat.event.ChatMessageEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

  // Redis 채널에 메시지 발행 (Kafka Consumer → Redis)
  public void publish(ChatMessageEvent event) {
    try {
      String channel = RedisConfig.CHAT_ROOM_CHANNEL_PREFIX + event.getRoomId();
      String message = objectMapper.writeValueAsString(event);
      redisTemplate.convertAndSend(channel, message);
      log.debug("Redis 발행: channel={}, messageKey={}", channel, event.getMessageKey());
    } catch (Exception e) {
      log.error("Redis 발행 실패: messageKey={}", event.getMessageKey(), e);
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
    }
  }

  // Redis 구독 Presence 이벤트 수신 → STOMP로 전체 브로드캐스트
  public void onPresenceMessage(String message, String channel) {
    try {
      PresenceEvent event = objectMapper.readValue(message, PresenceEvent.class);
      messagingTemplate.convertAndSend("/topic/presence", event);
      log.debug("Presence 브로드캐스트: userId={}, status={}", event.getUserId(), event.getStatus());
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
    try {
      ChatMessageEvent event = objectMapper.readValue(message, ChatMessageEvent.class);
      String roomId = channel.replace(RedisConfig.CHAT_ROOM_CHANNEL_PREFIX, "");
      String destination = "/topic/room." + roomId;

      messagingTemplate.convertAndSend(destination, event);
      log.debug(
          "WebSocket 브로드캐스트: destination={}, messageKey={}", destination, event.getMessageKey());
    } catch (Exception e) {
      log.error("WebSocket 브로드캐스트 실패: channel={}", channel, e);
    }
  }
}
