package com.realtime.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

// 온라인/오프라인 상태 변경 이벤트
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class PresenceEvent {

    private Long userId;
    private String status; // "ONLINE" or "OFFLINE"
    private long timestamp;

    public static PresenceEvent online(Long userId) {
        return new PresenceEvent(userId, "ONLINE", System.currentTimeMillis());
    }

    public static PresenceEvent offline(Long userId) {
        return new PresenceEvent(userId, "OFFLINE", System.currentTimeMillis());
    }
}
