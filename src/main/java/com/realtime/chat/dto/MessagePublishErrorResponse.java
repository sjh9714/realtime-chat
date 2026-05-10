package com.realtime.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.UUID;

@Getter
@AllArgsConstructor
public class MessagePublishErrorResponse {

    private UUID clientMessageId;
    private Long roomId;
    private MessagePublishStatus status;
    private String reason;

    public static MessagePublishErrorResponse failed(UUID clientMessageId, Long roomId, String reason) {
        return new MessagePublishErrorResponse(
                clientMessageId,
                roomId,
                MessagePublishStatus.FAILED,
                reason
        );
    }
}
