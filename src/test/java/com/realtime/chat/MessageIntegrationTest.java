package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.realtime.chat.domain.*;
import com.realtime.chat.dto.*;
import com.realtime.chat.repository.*;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;

class MessageIntegrationTest extends BaseIntegrationTest {

  @LocalServerPort private int port;

  @Autowired private TestRestTemplate restTemplate;

  @Autowired private UserRepository userRepository;

  @Autowired private ChatRoomRepository chatRoomRepository;

  @Autowired private ChatRoomMemberRepository chatRoomMemberRepository;

  @Autowired private MessageRepository messageRepository;

  private String baseUrl;
  private String token1;
  private String token3;
  private User user1;
  private User user2;
  private User user3;
  private ChatRoom room;

  @BeforeEach
  void setUp() {
    baseUrl = "http://localhost:" + port;
    messageRepository.deleteAll();
    chatRoomMemberRepository.deleteAll();
    chatRoomRepository.deleteAll();
    userRepository.deleteAll();

    // 유저 생성
    token1 = signup("user1@test.com", "password123", "유저1");
    signup("user2@test.com", "password123", "유저2");
    token3 = signup("user3@test.com", "password123", "유저3");
    user1 = userRepository.findByEmail("user1@test.com").get();
    user2 = userRepository.findByEmail("user2@test.com").get();
    user3 = userRepository.findByEmail("user3@test.com").get();

    // 1:1 채팅방 생성
    CreateDirectRoomRequest directRequest = new CreateDirectRoomRequest();
    ReflectionTestUtils.setField(directRequest, "targetUserId", user2.getId());
    ResponseEntity<ChatRoomResponse> roomResponse =
        postWithAuth("/api/rooms/direct", directRequest, ChatRoomResponse.class, token1);
    room = chatRoomRepository.findById(roomResponse.getBody().getId()).get();
  }

  @Test
  @DisplayName("메시지 30건 저장 → 커서 페이지네이션 → 읽음 처리")
  void messageFlowWithPagination() {
    // 30건 메시지 직접 저장 (Kafka 우회, DB 직접 테스트)
    for (int i = 0; i < 30; i++) {
      Message message = new Message(UUID.randomUUID(), room, user1, "메시지 " + i, MessageType.TEXT);
      messageRepository.save(message);
    }

    // 첫 페이지 (20건)
    ResponseEntity<MessagePageResponse> page1 =
        getWithAuth(
            "/api/rooms/" + room.getId() + "/messages?size=20", MessagePageResponse.class, token1);

    assertThat(page1.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(page1.getBody().getMessages()).hasSize(20);
    assertThat(page1.getBody().isHasMore()).isTrue();
    assertThat(page1.getBody().getNextCursor()).isNotNull();

    // 두 번째 페이지 (나머지 10건)
    Long cursor = page1.getBody().getNextCursor();
    ResponseEntity<MessagePageResponse> page2 =
        getWithAuth(
            "/api/rooms/" + room.getId() + "/messages?cursor=" + cursor + "&size=20",
            MessagePageResponse.class,
            token1);

    assertThat(page2.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(page2.getBody().getMessages()).hasSize(10);
    assertThat(page2.getBody().isHasMore()).isFalse();

    // 읽음 처리
    Long lastMessageId = page1.getBody().getMessages().get(0).getId();
    ReadReceiptRequest readRequest = new ReadReceiptRequest();
    ReflectionTestUtils.setField(readRequest, "lastReadMessageId", lastMessageId);

    ResponseEntity<Void> readResponse =
        postWithAuth("/api/rooms/" + room.getId() + "/read", readRequest, Void.class, token1);

    assertThat(readResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
  }

  @Test
  @DisplayName("멱등성 체크: 동일 messageKey 중복 저장 방지")
  void idempotencyCheck() {
    UUID messageKey = UUID.randomUUID();
    Message message1 = new Message(messageKey, room, user1, "테스트 메시지", MessageType.TEXT);
    messageRepository.save(message1);

    // 동일 messageKey로 존재 여부 확인
    assertThat(messageRepository.existsByMessageKey(messageKey)).isTrue();
  }

  @Test
  @DisplayName("sync API는 afterMessageId 이후 메시지를 id 오름차순으로 반환한다")
  void syncMessagesAfterMessageIdInAscendingOrder() {
    var messages = saveMessages(5);

    ResponseEntity<MessageSyncResponse> response =
        getWithAuth(
            "/api/rooms/" + room.getId() + "/messages/sync?afterMessageId="
                + messages.get(1).getId()
                + "&limit=2",
            MessageSyncResponse.class,
            token1);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().getMessages())
        .extracting(MessageResponse::getId)
        .containsExactly(messages.get(2).getId(), messages.get(3).getId());
    assertThat(response.getBody().isHasMore()).isTrue();
    assertThat(response.getBody().getLastMessageId()).isEqualTo(messages.get(3).getId());
  }

  @Test
  @DisplayName("sync API는 afterMessageId가 없으면 최신 메시지 묶음을 id 오름차순으로 반환한다")
  void syncMessagesWithoutAfterMessageIdReturnsRecentMessagesAscending() {
    var messages = saveMessages(5);

    ResponseEntity<MessageSyncResponse> response =
        getWithAuth(
            "/api/rooms/" + room.getId() + "/messages/sync?limit=3",
            MessageSyncResponse.class,
            token1);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getBody().getMessages())
        .extracting(MessageResponse::getId)
        .containsExactly(messages.get(2).getId(), messages.get(3).getId(), messages.get(4).getId());
    assertThat(response.getBody().isHasMore()).isTrue();
    assertThat(response.getBody().getLastMessageId()).isEqualTo(messages.get(4).getId());
  }

  @Test
  @DisplayName("sync API는 비멤버 접근을 거부한다")
  void syncMessagesRejectsNonMember() {
    saveMessages(1);

    ResponseEntity<String> response =
        getWithAuth("/api/rooms/" + room.getId() + "/messages/sync", String.class, token3);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
  }

  @Test
  @DisplayName("sync API는 다른 방의 afterMessageId를 거부한다")
  void syncMessagesRejectsAfterMessageIdFromOtherRoom() {
    Message otherRoomMessage = saveMessageInOtherRoom();

    ResponseEntity<String> response =
        getWithAuth(
            "/api/rooms/" + room.getId() + "/messages/sync?afterMessageId="
                + otherRoomMessage.getId(),
            String.class,
            token1);

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
  }

  @Test
  @DisplayName("sync API는 1 미만 limit을 거부하고 100 초과 limit은 100으로 제한한다")
  void syncMessagesValidatesAndCapsLimit() {
    saveMessages(105);

    ResponseEntity<String> badLimitResponse =
        getWithAuth("/api/rooms/" + room.getId() + "/messages/sync?limit=0", String.class, token1);

    assertThat(badLimitResponse.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

    ResponseEntity<MessageSyncResponse> cappedLimitResponse =
        getWithAuth(
            "/api/rooms/" + room.getId() + "/messages/sync?limit=101",
            MessageSyncResponse.class,
            token1);

    assertThat(cappedLimitResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(cappedLimitResponse.getBody().getMessages()).hasSize(100);
    assertThat(cappedLimitResponse.getBody().isHasMore()).isTrue();
  }

  private java.util.List<Message> saveMessages(int count) {
    java.util.List<Message> messages = new java.util.ArrayList<>();
    for (int i = 0; i < count; i++) {
      Message message = new Message(UUID.randomUUID(), room, user1, "sync 메시지 " + i, MessageType.TEXT);
      messages.add(messageRepository.saveAndFlush(message));
    }
    return messages;
  }

  private Message saveMessageInOtherRoom() {
    ChatRoom otherRoom = new ChatRoom(null, RoomType.DIRECT, user1);
    otherRoom.addMember(user1);
    otherRoom.addMember(user3);
    chatRoomRepository.saveAndFlush(otherRoom);

    Message message =
        new Message(UUID.randomUUID(), otherRoom, user1, "다른 방 메시지", MessageType.TEXT);
    return messageRepository.saveAndFlush(message);
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

  private <T> ResponseEntity<T> getWithAuth(String path, Class<T> responseType, String token) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return restTemplate.exchange(
        baseUrl + path, HttpMethod.GET, new HttpEntity<>(headers), responseType);
  }
}
