package com.realtime.chat.dto;

import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class MessageSyncResponse {

  private List<MessageResponse> messages;
  private boolean hasMore;
  private Long lastMessageId;
}
