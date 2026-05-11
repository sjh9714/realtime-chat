package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;

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

class WebSocketAckIntegrationTest extends BaseIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private JwtTokenProvider jwtTokenProvider;

  @Autowired private UserRepository userRepository;

  @Autowired private ChatRoomRepository chatRoomRepository;

  @Autowired private ChatRoomMemberRepository chatRoomMemberRepository;

  @Autowired private MessageRepository messageRepository;

  @BeforeEach
  void setUp() {
    messageRepository.deleteAll();
    chatRoomMemberRepository.deleteAll();
    chatRoomRepository.deleteAll();
    userRepository.deleteAll();
  }

  @Test
  @DisplayName("STOMP client는 Kafka publish 성공 ACK를 user destination으로 수신한다")
  void stompClientReceivesKafkaPublishAckOnUserDestination() throws Exception {
    User user = userRepository.save(new User("ack@test.com", "encoded", "ACK유저"));
    ChatRoom room = new ChatRoom(null, RoomType.DIRECT, user);
    room.addMember(user);
    room = chatRoomRepository.saveAndFlush(room);
    String token = jwtTokenProvider.createToken(user.getId(), user.getEmail());
    UUID clientMessageId = UUID.randomUUID();
    BlockingQueue<Map<String, Object>> ackMessages = new LinkedBlockingQueue<>();

    WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
    stompClient.setMessageConverter(new MappingJackson2MessageConverter());

    StompHeaders connectHeaders = new StompHeaders();
    connectHeaders.add("Authorization", "Bearer " + token);

    StompSession session =
        stompClient
            .connectAsync(
                "ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                connectHeaders,
                new StompSessionHandlerAdapter() {})
            .get(5, TimeUnit.SECONDS);

    try {
      session.subscribe(
          "/user/queue/messages/ack",
          new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
              return Map.class;
            }

            @Override
            @SuppressWarnings("unchecked")
            public void handleFrame(StompHeaders headers, Object payload) {
              ackMessages.add((Map<String, Object>) payload);
            }
          });

      session.send(
          "/app/chat.send",
          Map.of(
              "clientMessageId", clientMessageId.toString(),
              "roomId", room.getId(),
              "content", "ACK integration message",
              "type", MessageType.TEXT.name()));

      Map<String, Object> ack = ackMessages.poll(10, TimeUnit.SECONDS);

      assertThat(ack).isNotNull();
      assertThat(ack.get("clientMessageId")).isEqualTo(clientMessageId.toString());
      assertThat(((Number) ack.get("roomId")).longValue()).isEqualTo(room.getId());
      assertThat(ack.get("status")).isEqualTo(MessagePublishStatus.ACCEPTED.name());
      assertThat(ack.get("acceptedAt")).isNotNull();
    } finally {
      session.disconnect();
      stompClient.stop();
    }
  }
}
