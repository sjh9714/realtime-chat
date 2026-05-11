package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.realtime.chat.config.KafkaConfig;
import com.realtime.chat.domain.*;
import com.realtime.chat.dto.AuthResponse;
import com.realtime.chat.dto.ChatRoomResponse;
import com.realtime.chat.dto.CreateDirectRoomRequest;
import com.realtime.chat.dto.SignupRequest;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.repository.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;

class ConsumerRecoveryIntegrationTest extends BaseIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private UserRepository userRepository;

  @Autowired private ChatRoomRepository chatRoomRepository;

  @Autowired private ChatRoomMemberRepository chatRoomMemberRepository;

  @Autowired private MessageRepository messageRepository;

  @Autowired private KafkaTemplate<String, Object> kafkaTemplate;

  private String baseUrl;
  private String token1;
  private User user1;
  private User user2;
  private ChatRoom room;

  @BeforeEach
  void setUp() {
    baseUrl = "http://localhost:" + port;
    messageRepository.deleteAll();
    chatRoomMemberRepository.deleteAll();
    chatRoomRepository.deleteAll();
    userRepository.deleteAll();

    token1 = signup("user1@test.com", "password123", "유저1");
    signup("user2@test.com", "password123", "유저2");
    user1 = userRepository.findByEmail("user1@test.com").get();
    user2 = userRepository.findByEmail("user2@test.com").get();

    CreateDirectRoomRequest directRequest = new CreateDirectRoomRequest();
    ReflectionTestUtils.setField(directRequest, "targetUserId", user2.getId());
    ResponseEntity<ChatRoomResponse> roomResponse =
        postWithAuth("/api/rooms/direct", directRequest, ChatRoomResponse.class, token1);
    room = chatRoomRepository.findById(roomResponse.getBody().getId()).get();
  }

  @Test
  @DisplayName("멱등성 심화: 동일 messageKey를 Kafka로 2회 발행 → DB에 1건만 저장")
  void duplicateMessageKey_shouldSaveOnlyOnce() {
    UUID messageKey = UUID.randomUUID();
    ChatMessageEvent event =
        new ChatMessageEvent(
            messageKey,
            room.getId(),
            user1.getId(),
            "유저1",
            "중복 테스트 메시지",
            MessageType.TEXT,
            java.time.LocalDateTime.now());

    // 동일 메시지를 2회 발행
    kafkaTemplate.send(KafkaConfig.MESSAGES_TOPIC, String.valueOf(room.getId()), event);
    kafkaTemplate.send(KafkaConfig.MESSAGES_TOPIC, String.valueOf(room.getId()), event);

    // Consumer가 처리할 시간 대기 후 DB 확인
    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              long count = messageRepository.countByMessageKey(messageKey);
              assertThat(count).isEqualTo(1);
            });
  }

  @Test
  @DisplayName("Kafka → Consumer → DB 저장 E2E 검증")
  void kafkaToDbEndToEnd() {
    UUID messageKey = UUID.randomUUID();
    ChatMessageEvent event =
        new ChatMessageEvent(
            messageKey,
            room.getId(),
            user1.getId(),
            "유저1",
            "E2E 테스트",
            MessageType.TEXT,
            java.time.LocalDateTime.now());

    kafkaTemplate.send(KafkaConfig.MESSAGES_TOPIC, String.valueOf(room.getId()), event);

    await()
        .atMost(10, TimeUnit.SECONDS)
        .untilAsserted(
            () -> {
              assertThat(messageRepository.existsByMessageKey(messageKey)).isTrue();
              Message saved = messageRepository.findByMessageKey(messageKey).get();
              assertThat(saved.getContent()).isEqualTo("E2E 테스트");
              assertThat(saved.getChatRoom().getId()).isEqualTo(room.getId());
            });
  }

  private String signup(String email, String password, String nickname) {
    SignupRequest request = new SignupRequest();
    ReflectionTestUtils.setField(request, "email", email);
    ReflectionTestUtils.setField(request, "password", password);
    ReflectionTestUtils.setField(request, "nickname", nickname);
    ResponseEntity<AuthResponse> response =
        restTemplate.postForEntity(baseUrl + "/api/auth/signup", request, AuthResponse.class);
    return response.getBody().getToken();
  }

  private <T> ResponseEntity<T> postWithAuth(
      String path, Object body, Class<T> responseType, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    headers.setContentType(MediaType.APPLICATION_JSON);
    return restTemplate.exchange(
        baseUrl + path, HttpMethod.POST, new HttpEntity<>(body, headers), responseType);
  }
}
