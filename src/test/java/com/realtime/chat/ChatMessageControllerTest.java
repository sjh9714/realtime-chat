package com.realtime.chat;

import com.realtime.chat.controller.ChatMessageController;
import com.realtime.chat.domain.MessageType;
import com.realtime.chat.domain.User;
import com.realtime.chat.dto.MessagePublishAckResponse;
import com.realtime.chat.dto.MessagePublishErrorResponse;
import com.realtime.chat.dto.MessagePublishStatus;
import com.realtime.chat.dto.SendMessageRequest;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.producer.ChatMessageProducer;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.support.SendResult;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.Principal;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ChatMessageControllerTest {

    @Mock
    private ChatMessageProducer chatMessageProducer;

    @Mock
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private Counter messagesSentCounter;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Test
    @DisplayName("Kafka publish 성공 시 sender에게 ACCEPTED ACK를 보낸다")
    void sendAckWhenKafkaPublishSucceeds() {
        UUID clientMessageId = UUID.randomUUID();
        ChatMessageController controller = controller();
        SendMessageRequest request = sendRequest(20L, "안녕하세요", clientMessageId);
        givenMemberAndSender();
        given(chatMessageProducer.sendMessage(any(ChatMessageEvent.class)))
                .willReturn(CompletableFuture.completedFuture(sendResult()));

        controller.sendMessage(request, principal("10"));

        ArgumentCaptor<MessagePublishAckResponse> captor =
                ArgumentCaptor.forClass(MessagePublishAckResponse.class);
        verify(messagingTemplate).convertAndSendToUser(eq("10"), eq("/queue/messages/ack"), captor.capture());
        MessagePublishAckResponse response = captor.getValue();
        assertThat(response.getClientMessageId()).isEqualTo(clientMessageId);
        assertThat(response.getRoomId()).isEqualTo(20L);
        assertThat(response.getStatus()).isEqualTo(MessagePublishStatus.ACCEPTED);
        assertThat(response.getAcceptedAt()).isNotNull();
        verify(messagesSentCounter).increment();
    }

    @Test
    @DisplayName("Kafka publish 실패 시 sender에게 FAILED NACK를 보낸다")
    void sendNackWhenKafkaPublishFails() {
        UUID clientMessageId = UUID.randomUUID();
        ChatMessageController controller = controller();
        SendMessageRequest request = sendRequest(20L, "안녕하세요", clientMessageId);
        CompletableFuture<SendResult<String, Object>> failedFuture = new CompletableFuture<>();
        failedFuture.completeExceptionally(new RuntimeException("kafka unavailable"));
        givenMemberAndSender();
        given(chatMessageProducer.sendMessage(any(ChatMessageEvent.class))).willReturn(failedFuture);

        controller.sendMessage(request, principal("10"));

        ArgumentCaptor<MessagePublishErrorResponse> captor =
                ArgumentCaptor.forClass(MessagePublishErrorResponse.class);
        verify(messagingTemplate).convertAndSendToUser(eq("10"), eq("/queue/messages/error"), captor.capture());
        MessagePublishErrorResponse response = captor.getValue();
        assertThat(response.getClientMessageId()).isEqualTo(clientMessageId);
        assertThat(response.getRoomId()).isEqualTo(20L);
        assertThat(response.getStatus()).isEqualTo(MessagePublishStatus.FAILED);
        assertThat(response.getReason()).contains("kafka unavailable");
        verify(messagesSentCounter, never()).increment();
    }

    @Test
    @DisplayName("clientMessageId가 없으면 서버가 생성한 UUID를 ACK에 포함한다")
    void generateClientMessageIdWhenMissing() {
        ChatMessageController controller = controller();
        SendMessageRequest request = sendRequest(20L, "안녕하세요", null);
        givenMemberAndSender();
        given(chatMessageProducer.sendMessage(any(ChatMessageEvent.class)))
                .willReturn(CompletableFuture.completedFuture(sendResult()));

        controller.sendMessage(request, principal("10"));

        ArgumentCaptor<MessagePublishAckResponse> captor =
                ArgumentCaptor.forClass(MessagePublishAckResponse.class);
        verify(messagingTemplate).convertAndSendToUser(eq("10"), eq("/queue/messages/ack"), captor.capture());
        assertThat(captor.getValue().getClientMessageId()).isNotNull();
    }

    private ChatMessageController controller() {
        return new ChatMessageController(
                chatMessageProducer,
                chatRoomMemberRepository,
                userRepository,
                messagesSentCounter,
                messagingTemplate
        );
    }

    private void givenMemberAndSender() {
        User sender = new User("user@test.com", "encoded", "유저");
        ReflectionTestUtils.setField(sender, "id", 10L);
        given(chatRoomMemberRepository.existsByChatRoomIdAndUserId(20L, 10L)).willReturn(true);
        given(userRepository.findById(10L)).willReturn(Optional.of(sender));
    }

    private SendMessageRequest sendRequest(Long roomId, String content, UUID clientMessageId) {
        SendMessageRequest request = new SendMessageRequest();
        ReflectionTestUtils.setField(request, "roomId", roomId);
        ReflectionTestUtils.setField(request, "content", content);
        ReflectionTestUtils.setField(request, "type", MessageType.TEXT);
        ReflectionTestUtils.setField(request, "clientMessageId", clientMessageId);
        return request;
    }

    private Principal principal(String name) {
        return () -> name;
    }

    @SuppressWarnings("unchecked")
    private SendResult<String, Object> sendResult() {
        return mock(SendResult.class);
    }
}
