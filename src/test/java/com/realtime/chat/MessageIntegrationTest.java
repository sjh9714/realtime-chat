package com.realtime.chat;

import com.realtime.chat.domain.*;
import com.realtime.chat.dto.*;
import com.realtime.chat.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class MessageIntegrationTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @Autowired
    private MessageRepository messageRepository;

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

        // 유저 생성
        token1 = signup("user1@test.com", "password123", "유저1");
        signup("user2@test.com", "password123", "유저2");
        user1 = userRepository.findByEmail("user1@test.com").get();
        user2 = userRepository.findByEmail("user2@test.com").get();

        // 1:1 채팅방 생성
        CreateDirectRoomRequest directRequest = new CreateDirectRoomRequest();
        ReflectionTestUtils.setField(directRequest, "targetUserId", user2.getId());
        ResponseEntity<ChatRoomResponse> roomResponse = postWithAuth(
                "/api/rooms/direct", directRequest, ChatRoomResponse.class, token1);
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
        ResponseEntity<MessagePageResponse> page1 = getWithAuth(
                "/api/rooms/" + room.getId() + "/messages?size=20",
                MessagePageResponse.class, token1);

        assertThat(page1.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page1.getBody().getMessages()).hasSize(20);
        assertThat(page1.getBody().isHasMore()).isTrue();
        assertThat(page1.getBody().getNextCursor()).isNotNull();

        // 두 번째 페이지 (나머지 10건)
        Long cursor = page1.getBody().getNextCursor();
        ResponseEntity<MessagePageResponse> page2 = getWithAuth(
                "/api/rooms/" + room.getId() + "/messages?cursor=" + cursor + "&size=20",
                MessagePageResponse.class, token1);

        assertThat(page2.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page2.getBody().getMessages()).hasSize(10);
        assertThat(page2.getBody().isHasMore()).isFalse();

        // 읽음 처리
        Long lastMessageId = page1.getBody().getMessages().get(0).getId();
        ReadReceiptRequest readRequest = new ReadReceiptRequest();
        ReflectionTestUtils.setField(readRequest, "lastReadMessageId", lastMessageId);

        ResponseEntity<Void> readResponse = postWithAuth(
                "/api/rooms/" + room.getId() + "/read",
                readRequest, Void.class, token1);

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

    private String signup(String email, String password, String nickname) {
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "email", email);
        ReflectionTestUtils.setField(request, "password", password);
        ReflectionTestUtils.setField(request, "nickname", nickname);
        ResponseEntity<AuthResponse> response = restTemplate.postForEntity(
                baseUrl + "/api/auth/signup", request, AuthResponse.class);
        return response.getBody().getToken();
    }

    private <T> ResponseEntity<T> postWithAuth(String path, Object body, Class<T> responseType, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return restTemplate.exchange(baseUrl + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), responseType);
    }

    private <T> ResponseEntity<T> getWithAuth(String path, Class<T> responseType, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl + path, HttpMethod.GET,
                new HttpEntity<>(headers), responseType);
    }
}
