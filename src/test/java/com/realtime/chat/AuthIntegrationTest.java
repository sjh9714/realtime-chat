package com.realtime.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.realtime.chat.dto.AuthResponse;
import com.realtime.chat.dto.LoginRequest;
import com.realtime.chat.dto.SignupRequest;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.ChatRoomRepository;
import com.realtime.chat.repository.MessageRepository;
import com.realtime.chat.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

class AuthIntegrationTest extends BaseIntegrationTest {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private ChatRoomMemberRepository chatRoomMemberRepository;

    @Autowired
    private ChatRoomRepository chatRoomRepository;

    @Autowired
    private ObjectMapper objectMapper;

    private String baseUrl;

    @BeforeEach
    void setUp() {
        baseUrl = "http://localhost:" + port + "/api/auth";
        messageRepository.deleteAll();
        chatRoomMemberRepository.deleteAll();
        chatRoomRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    @DisplayName("회원가입 → 로그인 → 토큰으로 보호된 API 접근")
    void signupAndLoginFlow() throws Exception {
        // 1. 회원가입
        SignupRequest signupRequest = new SignupRequest();
        ReflectionTestUtils.setField(signupRequest, "email", "test@test.com");
        ReflectionTestUtils.setField(signupRequest, "password", "password123");
        ReflectionTestUtils.setField(signupRequest, "nickname", "테스터");

        ResponseEntity<AuthResponse> signupResponse = restTemplate.postForEntity(
                baseUrl + "/signup", signupRequest, AuthResponse.class);

        assertThat(signupResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(signupResponse.getBody()).isNotNull();
        assertThat(signupResponse.getBody().getToken()).isNotBlank();
        assertThat(signupResponse.getBody().getEmail()).isEqualTo("test@test.com");

        // 2. 로그인
        LoginRequest loginRequest = new LoginRequest();
        ReflectionTestUtils.setField(loginRequest, "email", "test@test.com");
        ReflectionTestUtils.setField(loginRequest, "password", "password123");

        ResponseEntity<AuthResponse> loginResponse = restTemplate.postForEntity(
                baseUrl + "/login", loginRequest, AuthResponse.class);

        assertThat(loginResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(loginResponse.getBody()).isNotNull();
        assertThat(loginResponse.getBody().getToken()).isNotBlank();

        // 3. 토큰으로 보호된 API 접근
        String token = loginResponse.getBody().getToken();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<String> protectedResponse = restTemplate.exchange(
                "http://localhost:" + port + "/api/rooms",
                org.springframework.http.HttpMethod.GET,
                new HttpEntity<>(headers),
                String.class);

        assertThat(protectedResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // 4. 토큰 없이 보호된 API 접근 → 401
        ResponseEntity<String> unauthorizedResponse = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/rooms", String.class);

        assertThat(unauthorizedResponse.getStatusCode()).isIn(HttpStatus.UNAUTHORIZED, HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("중복 이메일로 회원가입 실패")
    void signupDuplicateEmail() {
        SignupRequest request = new SignupRequest();
        ReflectionTestUtils.setField(request, "email", "dup@test.com");
        ReflectionTestUtils.setField(request, "password", "password123");
        ReflectionTestUtils.setField(request, "nickname", "테스터");

        restTemplate.postForEntity(baseUrl + "/signup", request, AuthResponse.class);

        ResponseEntity<String> response = restTemplate.postForEntity(
                baseUrl + "/signup", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }
}
