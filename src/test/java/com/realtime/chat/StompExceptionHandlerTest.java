package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.realtime.chat.common.StompExceptionHandler;
import com.realtime.chat.dto.SendMessageRequest;
import com.realtime.chat.dto.StompErrorResponse;
import java.lang.reflect.Method;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.messaging.Message;
import org.springframework.messaging.handler.annotation.support.MethodArgumentNotValidException;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.FieldError;

class StompExceptionHandlerTest {

  @Test
  @DisplayName("STOMP validation 오류는 correlation id와 room id를 가진 구조화 payload다")
  void validationErrorKeepsMessageCorrelation() throws NoSuchMethodException {
    UUID clientMessageId = UUID.randomUUID();
    SendMessageRequest request = new SendMessageRequest();
    ReflectionTestUtils.setField(request, "clientMessageId", clientMessageId);
    ReflectionTestUtils.setField(request, "roomId", 20L);
    BeanPropertyBindingResult bindingResult =
        new BeanPropertyBindingResult(request, "sendMessageRequest");
    bindingResult.addError(
        new FieldError("sendMessageRequest", "content", "메시지 내용은 필수입니다."));
    Method handlerMethod =
        StompExceptionHandlerTest.class.getDeclaredMethod(
            "handleMessage", SendMessageRequest.class);
    Message<SendMessageRequest> message = MessageBuilder.withPayload(request).build();
    MethodArgumentNotValidException exception =
        new MethodArgumentNotValidException(
            message, new MethodParameter(handlerMethod, 0), bindingResult);

    StompErrorResponse response = new StompExceptionHandler().handleValidation(exception);

    assertThat(response.getCode()).isEqualTo("INVALID_MESSAGE");
    assertThat(response.getClientMessageId()).isEqualTo(clientMessageId);
    assertThat(response.getRoomId()).isEqualTo(20L);
    assertThat(response.getMessage()).contains("메시지 내용은 필수");
  }

  @SuppressWarnings("unused")
  private void handleMessage(SendMessageRequest request) {}
}
