package com.realtime.chat;

import com.realtime.chat.dto.*;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.ChatRoomRepository;
import com.realtime.chat.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ChatRoomIntegrationTest extends BaseIntegrationTest {

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

    private String baseUrl;
    private String token1;
    private String token2;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port;
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        userRepository.deleteAll();

        // 유저 2명 생성
        token1 = signup("user1@test.com", "password123", "유저1");
        token2 = signup("user2@test.com", "password123", "유저2");
    }

    @Test
    @DisplayName("1:1 채팅방 생성 → 그룹방 생성 → 참여 → 목록 조회")
    void chatRoomFlow() {
        // 1. 1:1 채팅방 생성
        CreateDirectRoomRequest directRequest = new CreateDirectRoomRequest();
        // user2의 ID를 찾아서 설정
        Long user2Id = userRepository.findByEmail("user2@test.com").get().getId();
        ReflectionTestUtils.setField(directRequest, "targetUserId", user2Id);

        ResponseEntity<ChatRoomResponse> directResponse = postWithAuth(
                "/api/rooms/direct", directRequest, ChatRoomResponse.class, token1);

        assertThat(directResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(directResponse.getBody().getMembers()).hasSize(2);

        // 2. 같은 1:1 방 재생성 시 기존 방 반환
        ResponseEntity<ChatRoomResponse> duplicateResponse = postWithAuth(
                "/api/rooms/direct", directRequest, ChatRoomResponse.class, token1);

        assertThat(duplicateResponse.getBody().getId()).isEqualTo(directResponse.getBody().getId());

        // 3. 그룹 채팅방 생성
        CreateGroupRoomRequest groupRequest = new CreateGroupRoomRequest();
        ReflectionTestUtils.setField(groupRequest, "name", "테스트 그룹");
        ReflectionTestUtils.setField(groupRequest, "memberIds", List.of(user2Id));

        ResponseEntity<ChatRoomResponse> groupResponse = postWithAuth(
                "/api/rooms/group", groupRequest, ChatRoomResponse.class, token1);

        assertThat(groupResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(groupResponse.getBody().getName()).isEqualTo("테스트 그룹");

        // 4. 내 채팅방 목록 조회
        ResponseEntity<List<ChatRoomListResponse>> listResponse = getListWithAuth(
                "/api/rooms", new ParameterizedTypeReference<>() {}, token1);

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody()).hasSize(2);

        // 5. 채팅방 상세 조회
        ResponseEntity<ChatRoomResponse> detailResponse = getWithAuth(
                "/api/rooms/" + groupResponse.getBody().getId(), ChatRoomResponse.class, token1);

        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detailResponse.getBody().getMembers()).hasSize(2);
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

    private <T> ResponseEntity<T> getListWithAuth(String path, ParameterizedTypeReference<T> responseType, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return restTemplate.exchange(baseUrl + path, HttpMethod.GET,
                new HttpEntity<>(headers), responseType);
    }
}
