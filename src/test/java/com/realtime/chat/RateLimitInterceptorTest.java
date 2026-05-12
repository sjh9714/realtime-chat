package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.realtime.chat.config.RateLimitInterceptor;
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
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

@ExtendWith(MockitoExtension.class)
class RateLimitInterceptorTest {

  private static final Clock FIXED_CLOCK =
      Clock.fixed(Instant.ofEpochSecond(1_710_000_000L), ZoneOffset.UTC);

  @Mock private StringRedisTemplate redisTemplate;
  @Mock private ValueOperations<String, String> valueOperations;

  @Test
  @DisplayName("SEND 명령은 Redis fixed window 제한 이하면 통과한다")
  void sendCommandWithinRedisLimitPasses() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.increment("rate:ws:send:user:10:1710000000")).thenReturn(1L, 2L);
    when(redisTemplate.expire("rate:ws:send:user:10:1710000000", Duration.ofSeconds(2)))
        .thenReturn(true);
    RateLimitInterceptor interceptor = new RateLimitInterceptor(redisTemplate, 2, FIXED_CLOCK);

    Message<byte[]> first = stompMessage(StompCommand.SEND);
    Message<byte[]> second = stompMessage(StompCommand.SEND);

    assertThat(interceptor.preSend(first, null)).isSameAs(first);
    assertThat(interceptor.preSend(second, null)).isSameAs(second);
    verify(redisTemplate)
        .expire("rate:ws:send:user:10:1710000000", Duration.ofSeconds(2));
  }

  @Test
  @DisplayName("SEND 명령은 Redis fixed window 제한을 초과하면 거부한다")
  void sendCommandOverRedisLimitIsRejected() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.increment("rate:ws:send:user:10:1710000000"))
        .thenReturn(1L, 2L, 3L);
    when(redisTemplate.expire("rate:ws:send:user:10:1710000000", Duration.ofSeconds(2)))
        .thenReturn(true);
    RateLimitInterceptor interceptor = new RateLimitInterceptor(redisTemplate, 2, FIXED_CLOCK);

    assertThat(interceptor.preSend(stompMessage(StompCommand.SEND), null)).isNotNull();
    assertThat(interceptor.preSend(stompMessage(StompCommand.SEND), null)).isNotNull();

    assertThatThrownBy(() -> interceptor.preSend(stompMessage(StompCommand.SEND), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("메시지 전송 속도 제한을 초과했습니다");
  }

  @Test
  @DisplayName("SUBSCRIBE 명령에는 SEND rate limit을 적용하지 않는다")
  void subscribeCommandIsNotRateLimited() {
    RateLimitInterceptor interceptor = new RateLimitInterceptor(redisTemplate, 1, FIXED_CLOCK);
    Message<byte[]> first = stompMessage(StompCommand.SUBSCRIBE);
    Message<byte[]> second = stompMessage(StompCommand.SUBSCRIBE);

    assertThat(interceptor.preSend(first, null)).isSameAs(first);
    assertThat(interceptor.preSend(second, null)).isSameAs(second);
    verifyNoInteractions(redisTemplate);
  }

  @Test
  @DisplayName("사용자별 Redis rate limit key는 분리된다")
  void redisRateLimitKeysAreSeparatedByUser() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.increment("rate:ws:send:user:10:1710000000")).thenReturn(1L);
    when(valueOperations.increment("rate:ws:send:user:11:1710000000")).thenReturn(1L);
    when(redisTemplate.expire("rate:ws:send:user:10:1710000000", Duration.ofSeconds(2)))
        .thenReturn(true);
    when(redisTemplate.expire("rate:ws:send:user:11:1710000000", Duration.ofSeconds(2)))
        .thenReturn(true);
    RateLimitInterceptor interceptor = new RateLimitInterceptor(redisTemplate, 1, FIXED_CLOCK);

    assertThat(interceptor.preSend(stompMessage(StompCommand.SEND, 10L), null)).isNotNull();
    assertThat(interceptor.preSend(stompMessage(StompCommand.SEND, 11L), null)).isNotNull();

    verify(valueOperations).increment("rate:ws:send:user:10:1710000000");
    verify(valueOperations).increment("rate:ws:send:user:11:1710000000");
  }

  @Test
  @DisplayName("최초 increment가 아니면 TTL을 다시 설정하지 않는다")
  void ttlIsSetOnlyForFirstIncrementInWindow() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.increment("rate:ws:send:user:10:1710000000")).thenReturn(2L);
    RateLimitInterceptor interceptor = new RateLimitInterceptor(redisTemplate, 10, FIXED_CLOCK);

    assertThat(interceptor.preSend(stompMessage(StompCommand.SEND), null)).isNotNull();

    verify(redisTemplate, never()).expire(anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  @DisplayName("Redis rate limit 체크 실패 시 fail-closed로 SEND를 거부한다")
  void redisFailureRejectsSendFailClosed() {
    when(redisTemplate.opsForValue()).thenReturn(valueOperations);
    when(valueOperations.increment("rate:ws:send:user:10:1710000000"))
        .thenThrow(new RedisConnectionFailureException("redis down"));
    RateLimitInterceptor interceptor = new RateLimitInterceptor(redisTemplate, 10, FIXED_CLOCK);

    assertThatThrownBy(() -> interceptor.preSend(stompMessage(StompCommand.SEND), null))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("메시지 전송 속도 제한을 초과했습니다");
  }

  private Message<byte[]> stompMessage(StompCommand command) {
    return stompMessage(command, 10L);
  }

  private Message<byte[]> stompMessage(StompCommand command, Long userId) {
    StompHeaderAccessor accessor = StompHeaderAccessor.create(command);
    accessor.setUser(new UsernamePasswordAuthenticationToken(userId, null, List.of()));
    return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
  }
}
