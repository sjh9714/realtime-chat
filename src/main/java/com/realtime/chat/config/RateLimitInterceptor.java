package com.realtime.chat.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import java.security.Principal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

// 유저별 초당 메시지 전송 제한 (메시지 폭탄 방지)
@Slf4j
@Component
public class RateLimitInterceptor implements ChannelInterceptor {

    private final int messagesPerSecond;

    // 유저별 카운터: userId → (카운트, 윈도우 시작 시각)
    private final ConcurrentHashMap<Long, RateWindow> rateLimitMap = new ConcurrentHashMap<>();

    public RateLimitInterceptor(@Value("${chat.rate-limit.messages-per-second:10}") int messagesPerSecond) {
        this.messagesPerSecond = messagesPerSecond;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        if (accessor == null || !StompCommand.SEND.equals(accessor.getCommand())) {
            return message;
        }

        Principal user = accessor.getUser();
        if (user == null) {
            return message;
        }

        Long userId = Long.parseLong(user.getName());
        RateWindow window = rateLimitMap.computeIfAbsent(userId, k -> new RateWindow());

        if (!window.tryAcquire(messagesPerSecond)) {
            log.warn("Rate limit 초과: userId={}, limit={}/sec", userId, messagesPerSecond);
            throw new IllegalStateException("메시지 전송 속도 제한을 초과했습니다.");
        }

        return message;
    }

    // 1초 윈도우 기반 카운터
    private static class RateWindow {
        private final AtomicLong windowStart = new AtomicLong(System.currentTimeMillis());
        private final AtomicInteger count = new AtomicInteger(0);

        boolean tryAcquire(int limit) {
            long now = System.currentTimeMillis();
            long start = windowStart.get();

            // 1초 경과 시 윈도우 리셋
            if (now - start >= 1000) {
                windowStart.set(now);
                count.set(1);
                return true;
            }

            return count.incrementAndGet() <= limit;
        }
    }
}
