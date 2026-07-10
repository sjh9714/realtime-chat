package com.realtime.chat.config;

import java.security.Principal;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
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
  private static final DefaultRedisScript<Long> INCREMENT_WITH_TTL_SCRIPT =
      new DefaultRedisScript<>(
          """
          local count = redis.call('INCR', KEYS[1])
          if count == 1 then
            redis.call('PEXPIRE', KEYS[1], ARGV[1])
          end
          return count
          """,
          Long.class);

  private final StringRedisTemplate redisTemplate;
  private final int messagesPerSecond;
  private final Clock clock;
  private final RateLimitFailureNotifier failureNotifier;

  @Autowired
  public RateLimitInterceptor(
      StringRedisTemplate redisTemplate,
      @Value("${chat.rate-limit.messages-per-second:10}") int messagesPerSecond,
      RateLimitFailureNotifier failureNotifier) {
    this(redisTemplate, messagesPerSecond, Clock.systemUTC(), failureNotifier);
  }

  public RateLimitInterceptor(
      StringRedisTemplate redisTemplate,
      int messagesPerSecond,
      Clock clock,
      RateLimitFailureNotifier failureNotifier) {
    this.redisTemplate = redisTemplate;
    this.messagesPerSecond = messagesPerSecond;
    this.clock = clock;
    this.failureNotifier = failureNotifier;
  }

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

    if (accessor == null || !StompCommand.SEND.equals(accessor.getCommand())) {
      return message;
    }
    if (!"/app/chat.send".equals(accessor.getDestination())) {
      return message;
    }

    Principal user = accessor.getUser();
    if (user == null) {
      return message;
    }

    Long userId = Long.parseLong(user.getName());
    long count;
    try {
      count = incrementRedisWindow(userId);
    } catch (IllegalStateException exception) {
      failureNotifier.notifyFailure(
          message,
          user,
          "RATE_LIMIT_UNAVAILABLE",
          "메시지 전송 제한을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요.");
      return null;
    }
    if (count > messagesPerSecond) {
      log.warn("Rate limit 초과: userId={}, limit={}/sec", userId, messagesPerSecond);
      failureNotifier.notifyFailure(
          message, user, "RATE_LIMITED", "메시지를 너무 빠르게 보내고 있습니다. 잠시 후 다시 시도해 주세요.");
      return null;
    }

    return message;
  }

  private long incrementRedisWindow(Long userId) {
    String key = rateLimitKey(userId);
    try {
      Long count =
          redisTemplate.execute(
              INCREMENT_WITH_TTL_SCRIPT,
              List.of(key),
              String.valueOf(WINDOW_TTL.toMillis()));
      if (count == null) {
        throw new IllegalStateException("Redis rate limit script returned null");
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
