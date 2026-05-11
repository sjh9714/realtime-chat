package com.realtime.chat.config;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

// 유저별 초당 메시지 전송 제한 (메시지 폭탄 방지)
@Slf4j
@Component
public class RateLimitInterceptor implements ChannelInterceptor {

  private static final String RATE_LIMIT_KEY_PREFIX = "rate:ws:send:user:";
  private static final Duration WINDOW_TTL = Duration.ofSeconds(2);

  private final StringRedisTemplate redisTemplate;
  private final int messagesPerSecond;
  private final Clock clock;

  @Autowired
  public RateLimitInterceptor(
      StringRedisTemplate redisTemplate,
      @Value("${chat.rate-limit.messages-per-second:10}") int messagesPerSecond) {
    this(redisTemplate, messagesPerSecond, Clock.systemUTC());
  }

  public RateLimitInterceptor(
      StringRedisTemplate redisTemplate, int messagesPerSecond, Clock clock) {
    this.redisTemplate = redisTemplate;
    this.messagesPerSecond = messagesPerSecond;
    this.clock = clock;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

    if (accessor == null || !StompCommand.SEND.equals(accessor.getCommand())) {
      return message;
    }

    Principal user = accessor.getUser();
    if (user == null) {
      return message;
    }

    Long userId = Long.parseLong(user.getName());
    long count = incrementRedisWindow(userId);
    if (count > messagesPerSecond) {
      log.warn("Rate limit 초과: userId={}, limit={}/sec", userId, messagesPerSecond);
      throw new IllegalStateException("메시지 전송 속도 제한을 초과했습니다.");
    }

    return message;
  }

  private long incrementRedisWindow(Long userId) {
    String key = rateLimitKey(userId);
    try {
      Long count = redisTemplate.opsForValue().increment(key);
      if (count == null) {
        throw new IllegalStateException("Redis rate limit increment returned null");
      }
      if (count == 1L) {
        Boolean ttlSet = redisTemplate.expire(key, WINDOW_TTL);
        if (!Boolean.TRUE.equals(ttlSet)) {
          throw new IllegalStateException("Redis rate limit TTL was not set");
        }
      }
      return count;
    } catch (Exception e) {
      log.warn("Redis rate limit 체크 실패: userId={}", userId, e);
      throw new IllegalStateException("메시지 전송 속도 제한을 초과했습니다.", e);
    }
  }

  private String rateLimitKey(Long userId) {
    return RATE_LIMIT_KEY_PREFIX + userId + ":" + clock.instant().getEpochSecond();
  }
}
