package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.realtime.chat.common.JwtTokenProvider;
import com.realtime.chat.domain.ChatRoom;
import com.realtime.chat.domain.MessageType;
import com.realtime.chat.domain.RoomType;
import com.realtime.chat.domain.User;
import com.realtime.chat.dto.MessagePublishStatus;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.ChatRoomRepository;
import com.realtime.chat.repository.MessageRepository;
import com.realtime.chat.repository.UserRepository;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

class WebSocketPersistedAckIntegrationTest extends BaseIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private JwtTokenProvider jwtTokenProvider;

  @Autowired private UserRepository userRepository;

  @Autowired private ChatRoomRepository chatRoomRepository;

  @Autowired private ChatRoomMemberRepository chatRoomMemberRepository;

  @Autowired private MessageRepository messageRepository;

  @BeforeEach
  void setUp() {
    messageRepository.deleteAllInBatch();
    chatRoomMemberRepository.deleteAllInBatch();
    chatRoomRepository.deleteAllInBatch();
    userRepository.deleteAllInBatch();
  }

  @Test
  @DisplayName("STOMP client는 DB 저장 완료 PERSISTED ACK를 user destination으로 수신한다")
  void stompClientReceivesPersistedAckOnUserDestination() throws Exception {
    User user = userRepository.save(new User("persisted@test.com", "encoded", "저장유저"));
    ChatRoom room = new ChatRoom(null, RoomType.DIRECT, user);
    room.addMember(user);
    room = chatRoomRepository.saveAndFlush(room);
    String token = jwtTokenProvider.createToken(user.getId(), user.getEmail());
    UUID clientMessageId = UUID.randomUUID();
    BlockingQueue<Map<String, Object>> persistedMessages = new LinkedBlockingQueue<>();

    WebSocketStompClient stompClient = stompClient();
    StompSession session = connect(stompClient, token);

    try {
      subscribePersisted(session, persistedMessages);
      sendMessage(session, clientMessageId, room.getId(), "PERSISTED integration message");

      Map<String, Object> persisted = persistedMessages.poll(15, TimeUnit.SECONDS);

      assertThat(persisted).isNotNull();
      assertThat(persisted.get("clientMessageId")).isEqualTo(clientMessageId.toString());
      assertThat(persisted.get("messageKey")).isNotNull();
      assertThat(((Number) persisted.get("messageId")).longValue()).isPositive();
      assertThat(((Number) persisted.get("roomId")).longValue()).isEqualTo(room.getId());
      assertThat(persisted.get("status")).isEqualTo(MessagePublishStatus.PERSISTED.name());
      assertThat(persisted.get("persistedAt")).isNotNull();
    } finally {
      session.disconnect();
      stompClient.stop();
    }
  }

  @Test
  @DisplayName("동일 clientMessageId 재시도는 DB 중복 없이 기존 메시지의 PERSISTED ACK를 다시 받을 수 있다")
  void duplicateClientRetryReceivesPersistedAckForExistingMessage() throws Exception {
    User user = userRepository.save(new User("retry@test.com", "encoded", "재시도유저"));
    ChatRoom room = new ChatRoom(null, RoomType.DIRECT, user);
    room.addMember(user);
    room = chatRoomRepository.saveAndFlush(room);
    String token = jwtTokenProvider.createToken(user.getId(), user.getEmail());
    UUID clientMessageId = UUID.randomUUID();
    BlockingQueue<Map<String, Object>> persistedMessages = new LinkedBlockingQueue<>();

    WebSocketStompClient stompClient = stompClient();
    StompSession session = connect(stompClient, token);

    try {
      subscribePersisted(session, persistedMessages);
      sendMessage(session, clientMessageId, room.getId(), "duplicate retry message");
      sendMessage(session, clientMessageId, room.getId(), "duplicate retry message");

      Map<String, Object> first = persistedMessages.poll(15, TimeUnit.SECONDS);
      Map<String, Object> second = persistedMessages.poll(15, TimeUnit.SECONDS);

      assertThat(first).isNotNull();
      assertThat(second).isNotNull();
      assertThat(first.get("clientMessageId")).isEqualTo(clientMessageId.toString());
      assertThat(second.get("clientMessageId")).isEqualTo(clientMessageId.toString());
      assertThat(second.get("messageId")).isEqualTo(first.get("messageId"));
      await()
          .atMost(5, TimeUnit.SECONDS)
          .untilAsserted(
              () ->
                  assertThat(
                          messageRepository.countBySenderIdAndClientMessageId(
                              user.getId(), clientMessageId))
                      .isEqualTo(1));
    } finally {
      session.disconnect();
      stompClient.stop();
    }
  }

  private WebSocketStompClient stompClient() {
    WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
    stompClient.setMessageConverter(new MappingJackson2MessageConverter());
    return stompClient;
  }

  private StompSession connect(WebSocketStompClient stompClient, String token) throws Exception {
    StompHeaders connectHeaders = new StompHeaders();
    connectHeaders.add("Authorization", "Bearer " + token);
    return stompClient
        .connectAsync(
            "ws://localhost:" + port + "/ws",
            new WebSocketHttpHeaders(),
            connectHeaders,
            new StompSessionHandlerAdapter() {})
        .get(5, TimeUnit.SECONDS);
  }

  private void subscribePersisted(
      StompSession session, BlockingQueue<Map<String, Object>> persistedMessages) {
    session.subscribe(
        "/user/queue/messages/persisted",
        new StompFrameHandler() {
          @Override
          public Type getPayloadType(StompHeaders headers) {
            return Map.class;
          }

          @Override
          @SuppressWarnings("unchecked")
          public void handleFrame(StompHeaders headers, Object payload) {
            persistedMessages.add((Map<String, Object>) payload);
          }
        });
  }

  private void sendMessage(
      StompSession session, UUID clientMessageId, Long roomId, String content) {
    session.send(
        "/app/chat.send",
        Map.of(
            "clientMessageId", clientMessageId.toString(),
            "roomId", roomId,
            "content", content,
            "type", MessageType.TEXT.name()));
  }
}
