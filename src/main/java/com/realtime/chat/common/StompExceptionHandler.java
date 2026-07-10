package com.realtime.chat.common;

import com.realtime.chat.dto.SendMessageRequest;
import com.realtime.chat.dto.StompErrorResponse;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.simp.annotation.SendToUser;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.annotation.ControllerAdvice;

@Slf4j
@ControllerAdvice
public class StompExceptionHandler {

  @MessageExceptionHandler(MethodArgumentNotValidException.class)
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public StompErrorResponse handleValidation(MethodArgumentNotValidException exception) {
    String message =
        exception.getBindingResult().getFieldErrors().stream()
            .map(FieldError::getDefaultMessage)
            .collect(Collectors.joining(", "));
    Object target = exception.getBindingResult().getTarget();
    SendMessageRequest request = target instanceof SendMessageRequest value ? value : null;
    return StompErrorResponse.of(
        "INVALID_MESSAGE",
        message,
        request != null ? request.getClientMessageId() : null,
        request != null ? request.getRoomId() : null);
  }

  @MessageExceptionHandler(BusinessException.class)
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public StompErrorResponse handleBusiness(BusinessException exception) {
    return StompErrorResponse.of("MESSAGE_NOT_ALLOWED", exception.getMessage(), null, null);
  }

  @MessageExceptionHandler(Exception.class)
  @SendToUser(destinations = "/queue/errors", broadcast = false)
  public StompErrorResponse handleUnexpected(Exception exception) {
    log.error("STOMP message handling failed", exception);
    return StompErrorResponse.of(
        "MESSAGE_HANDLING_FAILED", "메시지를 처리하지 못했습니다.", null, null);
  }
}
