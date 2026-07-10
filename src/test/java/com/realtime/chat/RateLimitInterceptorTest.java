package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.realtime.chat.config.RateLimitInterceptor;
import com.realtime.chat.config.RateLimitFailureNotifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.ofEpochSecond(1_710_000_000L), ZoneOffset.UTC);
  private static final String KEY = "rate:ws:send:user:10:1710000000";

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private RateLimitFailureNotifier failureNotifier;

  @Test
  @DisplayName("Lua script가 INCR와 최초 PEXPIRE를 한 원자 연산으로 실행한다")
  void sendCommandWithinRedisLimitPassesAtomically() {
    when(redisTemplate.execute(any(RedisScript.class), eq(List.of(KEY)), eq("2000")))
        .thenReturn(1L, 2L);
    RateLimitInterceptor interceptor =
        new RateLimitInterceptor(redisTemplate, 2, FIXED_CLOCK, failureNotifier);

    assertThat(interceptor.preSend(stompMessage(StompCommand.SEND), null)).isNotNull();
    assertThat(interceptor.preSend(stompMessage(StompCommand.SEND), null)).isNotNull();

    verify(redisTemplate, never()).expire(eq(KEY), any(Duration.class));
  }

  @Test
  @DisplayName("Lua script 결과가 제한을 초과하면 SEND를 거부한다")
  void sendCommandOverRedisLimitIsRejected() {
    when(redisTemplate.execute(any(RedisScript.class), eq(List.of(KEY)), eq("2000")))
        .thenReturn(3L);
    RateLimitInterceptor interceptor =
        new RateLimitInterceptor(redisTemplate, 2, FIXED_CLOCK, failureNotifier);
    Message<byte[]> message = stompMessage(StompCommand.SEND);

    assertThat(interceptor.preSend(message, null)).isNull();
    verify(failureNotifier)
        .notifyFailure(
            eq(message),
            any(),
            eq("RATE_LIMITED"),
            eq("메시지를 너무 빠르게 보내고 있습니다. 잠시 후 다시 시도해 주세요."));
  }

  @Test
  @DisplayName("SUBSCRIBE 명령에는 SEND rate limit을 적용하지 않는다")
  void subscribeCommandIsNotRateLimited() {
    RateLimitInterceptor interceptor =
        new RateLimitInterceptor(redisTemplate, 1, FIXED_CLOCK, failureNotifier);
    Message<byte[]> message = stompMessage(StompCommand.SUBSCRIBE);

    assertThat(interceptor.preSend(message, null)).isSameAs(message);
    verifyNoInteractions(redisTemplate);
  }

  @Test
  @DisplayName("사용자별 Redis rate limit key는 분리된다")
  void redisRateLimitKeysAreSeparatedByUser() {
    when(redisTemplate.execute(
            any(RedisScript.class),
            eq(List.of("rate:ws:send:user:10:1710000000")),
            eq("2000")))
        .thenReturn(1L);
    when(redisTemplate.execute(
            any(RedisScript.class),
            eq(List.of("rate:ws:send:user:11:1710000000")),
            eq("2000")))
        .thenReturn(1L);
    RateLimitInterceptor interceptor =
        new RateLimitInterceptor(redisTemplate, 1, FIXED_CLOCK, failureNotifier);

    assertThat(interceptor.preSend(stompMessage(StompCommand.SEND, 10L), null)).isNotNull();
    assertThat(interceptor.preSend(stompMessage(StompCommand.SEND, 11L), null)).isNotNull();
  }

  @Test
  @DisplayName("Redis rate limit 체크 실패 시 fail-closed로 SEND를 거부한다")
  void redisFailureRejectsSendFailClosed() {
    when(redisTemplate.execute(any(RedisScript.class), eq(List.of(KEY)), eq("2000")))
        .thenThrow(new RedisConnectionFailureException("redis down"));
    RateLimitInterceptor interceptor =
        new RateLimitInterceptor(redisTemplate, 10, FIXED_CLOCK, failureNotifier);
    Message<byte[]> message = stompMessage(StompCommand.SEND);

    assertThat(interceptor.preSend(message, null)).isNull();
    verify(failureNotifier)
        .notifyFailure(
            eq(message),
            any(),
            eq("RATE_LIMIT_UNAVAILABLE"),
            eq("메시지 전송 제한을 확인할 수 없습니다. 잠시 후 다시 시도해 주세요."));
  }

  private Message<byte[]> stompMessage(StompCommand command) {
    return stompMessage(command, 10L);
  }

  private Message<byte[]> stompMessage(StompCommand command, Long userId) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
    if (StompCommand.SEND.equals(command)) {
      accessor.setDestination("/app/chat.send");
    }
    accessor.setUser(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
