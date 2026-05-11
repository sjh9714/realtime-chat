package com.realtime.chat.service;

import com.realtime.chat.repository.ChatRoomMemberRepository;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

// Redis 기반 온라인/오프라인 상태 관리
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

  private static final String SESSION_KEY_FORMAT = "user:presence:%d:session:%s";
  private static final String SESSION_SET_KEY_FORMAT = "user:presence:%d:sessions";
  private static final String LEGACY_SESSION_ID = "legacy";
  private static final Duration PRESENCE_TTL = Duration.ofSeconds(60);

  private final StringRedisTemplate redisTemplate;
  private final ChatRoomMemberRepository chatRoomMemberRepository;

  // 온라인 상태 설정 (TTL 60초, heartbeat로 갱신)
  public void setOnline(Long userId) {
    setOnline(userId, LEGACY_SESSION_ID);
  }

  // session 단위 온라인 상태 설정. true면 기존 offline → online 전환이다.
  public boolean setOnline(Long userId, String sessionId) {
    boolean wasOnline = isOnline(userId);
    String sessionKey = sessionKey(userId, sessionId);
    String sessionSetKey = sessionSetKey(userId);

    redisTemplate.opsForValue().set(sessionKey, "ONLINE", PRESENCE_TTL);
    redisTemplate.opsForSet().add(sessionSetKey, sessionId);
    redisTemplate.expire(sessionSetKey, PRESENCE_TTL);

    log.debug("온라인 설정: userId={}", userId);
    return !wasOnline;
  }

  // 오프라인 상태 (키 삭제)
  public void setOffline(Long userId) {
    Set<String> sessionIds = redisTemplate.opsForSet().members(sessionSetKey(userId));
    if (sessionIds != null) {
      sessionIds.forEach(sessionId -> redisTemplate.delete(sessionKey(userId, sessionId)));
    }
    redisTemplate.delete(sessionSetKey(userId));
    log.debug("오프라인 설정: userId={}", userId);
  }

  // session 단위 오프라인 처리. true면 마지막 session이 끊겨 offline 전환이다.
  public boolean setOffline(Long userId, String sessionId) {
    redisTemplate.delete(sessionKey(userId, sessionId));
    redisTemplate.opsForSet().remove(sessionSetKey(userId), sessionId);

    boolean hasActiveSession = !activeSessionIds(userId).isEmpty();
    if (!hasActiveSession) {
      redisTemplate.delete(sessionSetKey(userId));
      log.debug("마지막 session 해제: userId={}, sessionId={}", userId, sessionId);
      return true;
    }

    log.debug("session 해제: userId={}, sessionId={}", userId, sessionId);
    return false;
  }

  // heartbeat로 현재 session TTL 갱신
  public boolean refreshSession(Long userId, String sessionId) {
    String sessionKey = sessionKey(userId, sessionId);
    if (!Boolean.TRUE.equals(redisTemplate.hasKey(sessionKey))) {
      return false;
    }

    String sessionSetKey = sessionSetKey(userId);
    redisTemplate.opsForValue().set(sessionKey, "ONLINE", PRESENCE_TTL);
    redisTemplate.opsForSet().add(sessionSetKey, sessionId);
    redisTemplate.expire(sessionSetKey, PRESENCE_TTL);
    log.debug("presence heartbeat 갱신: userId={}, sessionId={}", userId, sessionId);
    return true;
  }

  // 특정 유저의 온라인 여부 확인
  public boolean isOnline(Long userId) {
    return !activeSessionIds(userId).isEmpty();
  }

  // 채팅방 멤버 중 온라인인 유저 목록
  public Set<Long> getOnlineMembers(Long roomId) {
    List<Long> memberUserIds =
        chatRoomMemberRepository.findAllByChatRoomId(roomId).stream()
            .map(member -> member.getUser().getId())
            .collect(Collectors.toList());

    return memberUserIds.stream().filter(this::isOnline).collect(Collectors.toSet());
  }

  private Set<String> activeSessionIds(Long userId) {
    String sessionSetKey = sessionSetKey(userId);
    Set<String> sessionIds = redisTemplate.opsForSet().members(sessionSetKey);
    if (sessionIds == null || sessionIds.isEmpty()) {
      return Set.of();
    }

    Set<String> activeSessionIds = new HashSet<>();
    for (String sessionId : sessionIds) {
      if (Boolean.TRUE.equals(redisTemplate.hasKey(sessionKey(userId, sessionId)))) {
        activeSessionIds.add(sessionId);
      } else {
        redisTemplate.opsForSet().remove(sessionSetKey, sessionId);
      }
    }

    if (activeSessionIds.isEmpty()) {
      redisTemplate.delete(sessionSetKey);
    }
    return activeSessionIds;
  }

  private String sessionKey(Long userId, String sessionId) {
    return String.format(SESSION_KEY_FORMAT, userId, sessionId);
  }

  private String sessionSetKey(Long userId) {
    return String.format(SESSION_SET_KEY_FORMAT, userId);
  }
}
