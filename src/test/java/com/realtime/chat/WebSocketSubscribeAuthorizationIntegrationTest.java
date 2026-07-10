package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.realtime.chat.common.JwtTokenProvider;
import com.realtime.chat.domain.ChatRoom;
import com.realtime.chat.domain.RoomType;
import com.realtime.chat.domain.User;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.ChatRoomRepository;
import com.realtime.chat.repository.MessageRepository;
import com.realtime.chat.repository.UserRepository;
import java.lang.reflect.Type;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.messaging.converter.StringMessageConverter;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

class WebSocketSubscribeAuthorizationIntegrationTest extends BaseIntegrationTest {

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
  @DisplayName("채팅방 멤버가 아닌 사용자의 room topic 구독은 실제 STOMP 연결에서도 거부된다")
  void nonMemberSubscribeIsRejectedOverStomp() throws Exception {
    User userA = userRepository.save(new User("member@test.com", "encoded", "멤버"));
    User userB = userRepository.save(new User("outsider@test.com", "encoded", "비멤버"));
    ChatRoom room = new ChatRoom(null, RoomType.DIRECT, userA);
    room.addMember(userA);
    room = chatRoomRepository.saveAndFlush(room);
    String token = jwtTokenProvider.createToken(userB.getId(), userB.getEmail());
    BlockingQueue<String> authorizationFailures = new LinkedBlockingQueue<>();

    WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
    stompClient.setMessageConverter(new StringMessageConverter());
    StompHeaders connectHeaders = new StompHeaders();
    connectHeaders.add("Authorization", "Bearer " + token);
    CapturingSessionHandler sessionHandler = new CapturingSessionHandler(authorizationFailures);

    StompSession session =
        stompClient
            .connectAsync(
                "ws://localhost:" + port + "/ws",
                new WebSocketHttpHeaders(),
                connectHeaders,
                sessionHandler)
            .get(5, TimeUnit.SECONDS);

    try {
      StompHeaders subscribeHeaders = new StompHeaders();
      subscribeHeaders.setDestination("/topic/room." + room.getId());
      subscribeHeaders.setId("unauthorized-room-subscription");
      session.subscribe(subscribeHeaders, new NoopFrameHandler());

      await()
          .atMost(5, TimeUnit.SECONDS)
          .untilAsserted(
              () ->
                  assertThat(!authorizationFailures.isEmpty() || !session.isConnected()).isTrue());
    } finally {
      if (session.isConnected()) {
        session.disconnect();
      }
      stompClient.stop();
    }
  }

  @Test
  @DisplayName("비멤버가 broker topic으로 직접 SEND해도 멤버에게 forged payload가 전달되지 않는다")
  void outsiderCannotExploitDirectBrokerSend() throws Exception {
    User member = userRepository.save(new User("victim@test.com", "encoded", "피해자"));
    User outsider = userRepository.save(new User("attacker@test.com", "encoded", "공격자"));
    ChatRoom room = new ChatRoom(null, RoomType.DIRECT, member);
    room.addMember(member);
    room = chatRoomRepository.saveAndFlush(room);

    BlockingQueue<String> victimFrames = new LinkedBlockingQueue<>();
    BlockingQueue<String> attackerFailures = new LinkedBlockingQueue<>();
    WebSocketStompClient victimClient = stompClient();
    WebSocketStompClient attackerClient = stompClient();
    StompSession victimSession =
        connect(victimClient, jwtTokenProvider.createToken(member.getId(), member.getEmail()), null);
    StompSession attackerSession =
        connect(
            attackerClient,
            jwtTokenProvider.createToken(outsider.getId(), outsider.getEmail()),
            attackerFailures);

    try {
      victimSession.subscribe(
          "/topic/room." + room.getId(), new CollectingFrameHandler(victimFrames));
      Thread.sleep(200);

      attackerSession.send("/topic/room." + room.getId(), "forged-outsider-payload");

      await()
          .atMost(5, TimeUnit.SECONDS)
          .untilAsserted(
              () -> assertThat(!attackerFailures.isEmpty() || !attackerSession.isConnected()).isTrue());
      await()
          .during(700, TimeUnit.MILLISECONDS)
          .atMost(1, TimeUnit.SECONDS)
          .untilAsserted(() -> assertThat(victimFrames).isEmpty());
    } finally {
      if (victimSession.isConnected()) victimSession.disconnect();
      if (attackerSession.isConnected()) attackerSession.disconnect();
      victimClient.stop();
      attackerClient.stop();
    }
  }

  private WebSocketStompClient stompClient() {
    WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
    client.setMessageConverter(new StringMessageConverter());
    return client;
  }

  private StompSession connect(
      WebSocketStompClient client, String token, BlockingQueue<String> failures) throws Exception {
    StompHeaders connectHeaders = new StompHeaders();
    connectHeaders.add("Authorization", "Bearer " + token);
    StompSessionHandlerAdapter handler =
        failures == null ? new StompSessionHandlerAdapter() {} : new CapturingSessionHandler(failures);
    return client
        .connectAsync(
            "ws://localhost:" + port + "/ws",
            new WebSocketHttpHeaders(),
            connectHeaders,
            handler)
        .get(5, TimeUnit.SECONDS);
  }

  private static class CapturingSessionHandler extends StompSessionHandlerAdapter {

    private final BlockingQueue<String> authorizationFailures;

    private CapturingSessionHandler(BlockingQueue<String> authorizationFailures) {
      this.authorizationFailures = authorizationFailures;
    }

    @Override
    public Type getPayloadType(StompHeaders headers) {
      return String.class;
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      authorizationFailures.add("ERROR frame: " + payload);
    }

    @Override
    public void handleException(
        StompSession session,
        StompCommand command,
        StompHeaders headers,
        byte[] payload,
        Throwable exception) {
      authorizationFailures.add("exception: " + exception.getClass().getSimpleName());
    }

    @Override
    public void handleTransportError(StompSession session, Throwable exception) {
      authorizationFailures.add("transport: " + exception.getClass().getSimpleName());
    }
  }

  private static class NoopFrameHandler implements StompFrameHandler {

    @Override
    public Type getPayloadType(StompHeaders headers) {
      return String.class;
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {}
  }

  private static class CollectingFrameHandler implements StompFrameHandler {

    private final BlockingQueue<String> frames;

    private CollectingFrameHandler(BlockingQueue<String> frames) {
      this.frames = frames;
    }

    @Override
    public Type getPayloadType(StompHeaders headers) {
      return String.class;
    }

    @Override
    public void handleFrame(StompHeaders headers, Object payload) {
      frames.add(String.valueOf(payload));
    }
  }
}
