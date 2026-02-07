package com.realtime.chat;

import com.realtime.chat.dto.*;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.ChatRoomRepository;
import com.realtime.chat.repository.UserRepository;
import com.realtime.chat.service.PresenceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PresenceIntegrationTest extends BaseIntegrationTest {

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
    private PresenceService presenceService;

    private String baseUrl;
    private String token1;
    private Long user1Id;
    private Long user2Id;
    private Long roomId;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        userRepository.deleteAll();

        token1 = signup("user1@test.com", "password123", "유저1");
        signup("user2@test.com", "password123", "유저2");
        user1Id = userRepository.findByEmail("user1@test.com").get().getId();
        user2Id = userRepository.findByEmail("user2@test.com").get().getId();

        // 1:1 채팅방 생성
        CreateDirectRoomRequest directRequest = new CreateDirectRoomRequest();
        ReflectionTestUtils.setField(directRequest, "targetUserId", user2Id);
        ResponseEntity<ChatRoomResponse> roomResponse = postWithAuth(
                "/api/rooms/direct", directRequest, ChatRoomResponse.class, token1);
        roomId = roomResponse.getBody().getId();
    }

    @Test
    @DisplayName("온라인 상태 설정 → 확인 → 오프라인 → 확인")
    void presenceOnlineOffline() {
        // 처음에는 오프라인
        assertThat(presenceService.isOnline(user1Id)).isFalse();

        // 온라인 설정
        presenceService.setOnline(user1Id);
        assertThat(presenceService.isOnline(user1Id)).isTrue();

        // 오프라인 설정
        presenceService.setOffline(user1Id);
        assertThat(presenceService.isOnline(user1Id)).isFalse();
    }

    @Test
    @DisplayName("채팅방 멤버 중 온라인 유저 조회")
    void getOnlineMembers() {
        // user1만 온라인
        presenceService.setOnline(user1Id);

        Set<Long> onlineMembers = presenceService.getOnlineMembers(roomId);
        assertThat(onlineMembers).containsExactly(user1Id);

        // user2도 온라인
        presenceService.setOnline(user2Id);
        onlineMembers = presenceService.getOnlineMembers(roomId);
        assertThat(onlineMembers).containsExactlyInAnyOrder(user1Id, user2Id);

        // REST API 검증
        ResponseEntity<Set<Long>> response = restTemplate.exchange(
                baseUrl + "/api/rooms/" + roomId + "/members/online",
                HttpMethod.GET,
                new HttpEntity<>(authHeaders(token1)),
                new ParameterizedTypeReference<>() {});
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactlyInAnyOrder(user1Id, user2Id);
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

    private HttpHeaders authHeaders(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }
}
