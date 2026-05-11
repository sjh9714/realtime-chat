package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.service.PresenceService;
import java.time.Duration;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

  private static final Duration PRESENCE_TTL = Duration.ofSeconds(60);

  @Mock private StringRedisTemplate redisTemplate;

  @Mock private ValueOperations<String, String> valueOperations;

  @Mock private SetOperations<String, String> setOperations;

  @Mock private ChatRoomMemberRepository chatRoomMemberRepository;

  @Test
  @DisplayName("session 단위 online key와 user session set을 TTL과 함께 생성한다")
  void setOnlineCreatesSessionKeyAndSessionSet() {
    PresenceService service = service();
    givenAllRedisOperations();
    given(setOperations.members("user:presence:10:sessions")).willReturn(Set.of());

    boolean becameOnline = service.setOnline(10L, "s1");

    assertThat(becameOnline).isTrue();
    verify(valueOperations).set("user:presence:10:session:s1", "ONLINE", PRESENCE_TTL);
    verify(setOperations).add("user:presence:10:sessions", "s1");
    verify(redisTemplate).expire("user:presence:10:sessions", PRESENCE_TTL);
  }

  @Test
  @DisplayName("같은 user의 session 2개 중 1개 disconnect 후에도 online 상태를 유지한다")
  void disconnectOneSessionKeepsUserOnline() {
    PresenceService service = service();
    givenSetOperations();
    given(setOperations.members("user:presence:10:sessions")).willReturn(Set.of("s2"));
    given(redisTemplate.hasKey("user:presence:10:session:s2")).willReturn(true);

    boolean becameOffline = service.setOffline(10L, "s1");

    assertThat(becameOffline).isFalse();
    verify(redisTemplate).delete("user:presence:10:session:s1");
    verify(setOperations).remove("user:presence:10:sessions", "s1");
    verify(redisTemplate, never()).delete("user:presence:10:sessions");
  }

  @Test
  @DisplayName("마지막 session disconnect 시 offline 상태가 된다")
  void disconnectLastSessionMakesUserOffline() {
    PresenceService service = service();
    givenSetOperations();
    given(setOperations.members("user:presence:10:sessions")).willReturn(Set.of());

    boolean becameOffline = service.setOffline(10L, "s1");

    assertThat(becameOffline).isTrue();
    verify(redisTemplate).delete("user:presence:10:session:s1");
    verify(setOperations).remove("user:presence:10:sessions", "s1");
    verify(redisTemplate).delete("user:presence:10:sessions");
  }

  @Test
  @DisplayName("heartbeat는 살아있는 session key의 TTL을 갱신한다")
  void refreshSessionExtendsTtl() {
    PresenceService service = service();
    givenAllRedisOperations();
    given(redisTemplate.hasKey("user:presence:10:session:s1")).willReturn(true);

    boolean refreshed = service.refreshSession(10L, "s1");

    assertThat(refreshed).isTrue();
    verify(valueOperations).set("user:presence:10:session:s1", "ONLINE", PRESENCE_TTL);
    verify(redisTemplate).expire("user:presence:10:sessions", PRESENCE_TTL);
  }

  private PresenceService service() {
    return new PresenceService(redisTemplate, chatRoomMemberRepository);
  }

  private void givenAllRedisOperations() {
    given(redisTemplate.opsForValue()).willReturn(valueOperations);
    givenSetOperations();
  }

  private void givenSetOperations() {
    given(redisTemplate.opsForSet()).willReturn(setOperations);
  }
}
