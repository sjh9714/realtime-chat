package com.realtime.chat.service;

import com.realtime.chat.dto.MessageResponse;
import com.realtime.chat.event.ChatMessageEvent;

public interface PersistenceFailureProbe {

  void beforeDatabasePersist(ChatMessageEvent event);

  void beforeRedisPublish(MessageResponse message);
}
