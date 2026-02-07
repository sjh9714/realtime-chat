package com.realtime.chat.service;

import com.realtime.chat.repository.ChatRoomMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

// Redis 기반 온라인/오프라인 상태 관리
@Slf4j
@Service
@RequiredArgsConstructor
public class PresenceService {

    private static final String PRESENCE_KEY_PREFIX = "user:presence:";
    private static final Duration PRESENCE_TTL = Duration.ofSeconds(60);

    private final StringRedisTemplate redisTemplate;
    private final ChatRoomMemberRepository chatRoomMemberRepository;

    // 온라인 상태 설정 (TTL 60초, heartbeat로 갱신)
    public void setOnline(Long userId) {
        String key = PRESENCE_KEY_PREFIX + userId;
        redisTemplate.opsForValue().set(key, "ONLINE", PRESENCE_TTL);
        log.debug("온라인 설정: userId={}", userId);
    }

    // 오프라인 상태 (키 삭제)
    public void setOffline(Long userId) {
        String key = PRESENCE_KEY_PREFIX + userId;
        redisTemplate.delete(key);
        log.debug("오프라인 설정: userId={}", userId);
    }

    // 특정 유저의 온라인 여부 확인
    public boolean isOnline(Long userId) {
        String key = PRESENCE_KEY_PREFIX + userId;
        return Boolean.TRUE.equals(redisTemplate.hasKey(key));
    }

    // 채팅방 멤버 중 온라인인 유저 목록
    public Set<Long> getOnlineMembers(Long roomId) {
        List<Long> memberUserIds = chatRoomMemberRepository.findAllByChatRoomId(roomId)
                .stream()
                .map(member -> member.getUser().getId())
                .collect(Collectors.toList());

        return memberUserIds.stream()
                .filter(this::isOnline)
                .collect(Collectors.toSet());
    }
}
