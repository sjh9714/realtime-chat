package com.realtime.chat.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtime.chat.config.RedisConfig;
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
        }
    }

    // Redis 구독 메시지 수신 → STOMP로 WebSocket 클라이언트에게 브로드캐스트
    public void onMessage(String message, String channel) {
        try {
            ChatMessageEvent event = objectMapper.readValue(message, ChatMessageEvent.class);
            String roomId = channel.replace(RedisConfig.CHAT_ROOM_CHANNEL_PREFIX, "");
            String destination = "/topic/room." + roomId;

            messagingTemplate.convertAndSend(destination, event);
            log.debug("WebSocket 브로드캐스트: destination={}, messageKey={}", destination, event.getMessageKey());
        } catch (Exception e) {
            log.error("WebSocket 브로드캐스트 실패: channel={}", channel, e);
        }
    }
}
