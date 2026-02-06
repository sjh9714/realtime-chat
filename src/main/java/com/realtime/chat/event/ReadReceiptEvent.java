package com.realtime.chat.event;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

// Kafka 메시지 스키마: 읽음 처리
@Getter
@NoArgsConstructor
@AllArgsConstructor
public class ReadReceiptEvent {

    private Long roomId;
    private Long userId;
    private Long lastReadMessageId;
    private LocalDateTime timestamp;

    public static ReadReceiptEvent of(Long roomId, Long userId, Long lastReadMessageId) {
        return new ReadReceiptEvent(roomId, userId, lastReadMessageId, LocalDateTime.now());
    }
}
