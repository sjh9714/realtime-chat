package com.realtime.chat.controller;

import com.realtime.chat.common.BusinessException;
import com.realtime.chat.domain.User;
import com.realtime.chat.dto.MessagePublishAckResponse;
import com.realtime.chat.dto.MessagePublishErrorResponse;
import com.realtime.chat.dto.SendMessageRequest;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.producer.ChatMessageProducer;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import java.security.Principal;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;

// WebSocket STOMP 메시지 핸들러
@Slf4j
@Controller
@RequiredArgsConstructor
public class ChatMessageController {

  private static final String MESSAGE_ACK_DESTINATION = "/queue/messages/ack";
  private static final String MESSAGE_ERROR_DESTINATION = "/queue/messages/error";

  private final ChatMessageProducer chatMessageProducer;
  private final ChatRoomMemberRepository chatRoomMemberRepository;
  private final UserRepository userRepository;
  @Qualifier("messagesSentCounter")
  private final Counter messagesSentCounter;
  private final SimpMessagingTemplate messagingTemplate;

  // 클라이언트가 /app/chat.send로 메시지를 보내면 Kafka로 발행
  @MessageMapping("/chat.send")
  public void sendMessage(@Payload SendMessageRequest request, Principal principal) {
    Long userId = Long.parseLong(principal.getName());
    String userDestination = principal.getName();
    UUID clientMessageId = resolveClientMessageId(request);
    log.debug("메시지 수신: userId={}, roomId={}", userId, request.getRoomId());

    // 채팅방 멤버인지 확인
    if (!chatRoomMemberRepository.existsByChatRoomIdAndUserId(request.getRoomId(), userId)) {
      throw new BusinessException(HttpStatus.FORBIDDEN, "채팅방에 참여하지 않은 사용자입니다.");
    }

    User sender =
        userRepository
            .findById(userId)
            .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

    ChatMessageEvent event =
        ChatMessageEvent.of(
            request.getRoomId(),
            userId,
            sender.getNickname(),
            request.getContent(),
            request.getType(),
            clientMessageId);

    chatMessageProducer
        .sendMessage(event)
        .whenComplete(
            (result, ex) -> {
              if (ex != null) {
                String reason = failureReason(ex);
                log.warn(
                    "Kafka publish NACK 전송: userId={}, roomId={}, clientMessageId={}, reason={}",
                    userId,
                    request.getRoomId(),
                    clientMessageId,
                    reason);
                messagingTemplate.convertAndSendToUser(
                    userDestination,
                    MESSAGE_ERROR_DESTINATION,
                    MessagePublishErrorResponse.failed(
                        clientMessageId, request.getRoomId(), reason));
                return;
              }

              messagesSentCounter.increment();
              messagingTemplate.convertAndSendToUser(
                  userDestination,
                  MESSAGE_ACK_DESTINATION,
                  MessagePublishAckResponse.accepted(clientMessageId, request.getRoomId()));
            });
  }

  private UUID resolveClientMessageId(SendMessageRequest request) {
    return request.getClientMessageId() != null ? request.getClientMessageId() : UUID.randomUUID();
  }

  private String failureReason(Throwable ex) {
    Throwable cause =
        ex instanceof CompletionException && ex.getCause() != null ? ex.getCause() : ex;
    return cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
  }
}
