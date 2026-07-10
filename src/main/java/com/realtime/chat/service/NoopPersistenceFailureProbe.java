package com.realtime.chat.service;

import com.realtime.chat.dto.MessageResponse;
import com.realtime.chat.event.ChatMessageEvent;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("!e2e | prod")
public class NoopPersistenceFailureProbe implements PersistenceFailureProbe {

  @Override
  public void beforeDatabasePersist(ChatMessageEvent event) {}

  @Override
  public void beforeRedisPublish(MessageResponse message) {}
}
