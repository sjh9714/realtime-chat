package com.realtime.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.List;

@Getter
@AllArgsConstructor
public class MessagePageResponse {

    private List<MessageResponse> messages;
    private boolean hasMore;
    private Long nextCursor;
}
