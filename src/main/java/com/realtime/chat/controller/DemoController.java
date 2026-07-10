package com.realtime.chat.controller;

import com.realtime.chat.repository.MessageRepository;
import com.realtime.chat.service.DemoPersistenceFailureProbe;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("e2e & !prod")
@RequestMapping("/api/demo")
public class DemoController {

  private final DemoPersistenceFailureProbe failureProbe;
  private final MessageRepository messages;
  private final String instanceId;

  public DemoController(
      DemoPersistenceFailureProbe failureProbe,
      MessageRepository messages,
      @Value("${chat.e2e.instance-id}") String instanceId) {
    this.failureProbe = failureProbe;
    this.messages = messages;
    this.instanceId = instanceId;
  }

  @PostMapping("/failures/{stage}")
  public ResponseEntity<Void> armFailure(@PathVariable String stage) {
    failureProbe.arm(stage);
    return ResponseEntity.noContent().build();
  }

  @GetMapping("/messages/{clientMessageId}/count")
  public Map<String, Long> countByClientMessageId(@PathVariable UUID clientMessageId) {
    return Map.of("count", messages.countByClientMessageId(clientMessageId));
  }

  @GetMapping("/failures/{stage}/count")
  public Map<String, Long> failureCount(@PathVariable String stage) {
    return Map.of("count", failureProbe.consumedCount(stage));
  }

  @GetMapping("/instance")
  public Map<String, String> instance() {
    return Map.of("instance", instanceId);
  }
}
