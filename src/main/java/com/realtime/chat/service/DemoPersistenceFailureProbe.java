package com.realtime.chat.service;

import com.realtime.chat.dto.MessageResponse;
import com.realtime.chat.event.ChatMessageEvent;
import java.util.List;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

@Component
@Profile("e2e & !prod")
public class DemoPersistenceFailureProbe implements PersistenceFailureProbe {

  private static final String KEY_PREFIX = "e2e:fail-next:";
  private static final DefaultRedisScript<Long> CONSUME_SCRIPT =
      new DefaultRedisScript<>(
          "if redis.call('GET', KEYS[1]) then redis.call('DEL', KEYS[1]); return redis.call('INCR', KEYS[2]) else return 0 end",
          Long.class);

  private final StringRedisTemplate redisTemplate;

  public DemoPersistenceFailureProbe(StringRedisTemplate redisTemplate) {
    this.redisTemplate = redisTemplate;
  }

  public void arm(String stage) {
    validate(stage);
    redisTemplate.opsForValue().set(KEY_PREFIX + stage, "1");
  }

  public long consumedCount(String stage) {
    validate(stage);
    String value = redisTemplate.opsForValue().get(KEY_PREFIX + stage + ":consumed");
    return value == null ? 0L : Long.parseLong(value);
  }

  @Override
  public void beforeDatabasePersist(ChatMessageEvent event) {
    if (consume("database")) {
      throw new IllegalStateException("e2e injected database failure");
    }
  }

  @Override
  public void beforeRedisPublish(MessageResponse message) {
    if (consume("redis")) {
      throw new IllegalStateException("e2e injected Redis publish failure");
    }
  }

  private boolean consume(String stage) {
    Long count =
        redisTemplate.execute(
            CONSUME_SCRIPT, List.of(KEY_PREFIX + stage, KEY_PREFIX + stage + ":consumed"));
    return count != null && count > 0;
  }

  private void validate(String stage) {
    if (!List.of("database", "redis").contains(stage)) {
      throw new IllegalArgumentException("지원하지 않는 failure stage입니다.");
    }
  }
}
