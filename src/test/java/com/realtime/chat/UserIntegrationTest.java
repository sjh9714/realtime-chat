package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;

import com.realtime.chat.dto.AuthResponse;
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
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

class UserIntegrationTest extends BaseIntegrationTest {

  @LocalServerPort private int port;
  @Autowired private TestRestTemplate restTemplate;
  @Autowired private UserRepository userRepository;
  @Autowired private ChatRoomRepository chatRoomRepository;
  @Autowired private ChatRoomMemberRepository chatRoomMemberRepository;
  @Autowired private MessageRepository messageRepository;

  private String baseUrl;
  private String token;

  @BeforeEach
  void setUp() {
    baseUrl = "http://localhost:" + port;
    messageRepository.deleteAllInBatch();
    chatRoomMemberRepository.deleteAllInBatch();
    chatRoomRepository.deleteAllInBatch();
    userRepository.deleteAllInBatch();
    token = signup("me@test.com", "나");
    signup("minjun.private@test.com", "민준");
    signup("minseo.private@test.com", "민서");
  }

  @Test
  @DisplayName("/users/me와 닉네임 검색 응답은 이메일을 노출하지 않는다")
  void meAndNicknameSearchDoNotExposeEmails() {
    ResponseEntity<String> me = get("/api/users/me");
    ResponseEntity<String> search = get("/api/users/search?nickname=민준");

    assertThat(me.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(me.getBody()).contains("\"nickname\":\"나\"").doesNotContain("email", "@test.com");
    assertThat(search.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(search.getBody())
        .contains("\"nickname\":\"민준\"")
        .doesNotContain("private@test.com", "email");
  }

  private String signup(String email, String nickname) {
    SignupRequest request = new SignupRequest();
    ReflectionTestUtils.setField(request, "email", email);
    ReflectionTestUtils.setField(request, "password", "password123");
    ReflectionTestUtils.setField(request, "nickname", nickname);
    return restTemplate
        .postForEntity(baseUrl + "/api/auth/signup", request, AuthResponse.class)
        .getBody()
        .getToken();
  }

  private ResponseEntity<String> get(String path) {
    HttpHeaders headers = new HttpHeaders();
    headers.setBearerAuth(token);
    return restTemplate.exchange(
        baseUrl + path, HttpMethod.GET, new HttpEntity<>(headers), String.class);
  }
}
