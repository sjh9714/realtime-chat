package com.realtime.chat.controller;

import com.realtime.chat.common.JwtTokenProvider;
import com.realtime.chat.config.DemoDataConfig;
import com.realtime.chat.domain.User;
import com.realtime.chat.dto.AuthResponse;
import com.realtime.chat.repository.UserRepository;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 둘러보기 진입.
 *
 * <p><b>왜 필요한가.</b> 전에는 데모 진입이 언제나 {@code alice@demo.local}로 로그인했다.
 * 창을 두 개 열어도 둘 다 같은 사람이라 <b>메시지를 주고받을 수가 없었다.</b>
 * 실시간 전달이 이 제품의 전부인데 방문자는 그걸 한 번도 보지 못했다.
 *
 * <p>이제 데모 인물을 <b>돌아가며</b> 내준다. 창을 두 개 열면 서로 다른 사람이 되고,
 * 같은 그룹 방에서 실제로 대화가 오간다. 그때 보내는 중 → 전송됨 → 전달 완료가
 * 상대 화면에 실시간으로 도착하는 것을 볼 수 있다.
 *
 * <p>무작위가 아니라 순서대로 도는 이유: 무작위면 두 창이 같은 사람이 될 수 있다.
 *
 * <p>새 계정을 만들지 않는다. 인물은 이미 방의 참여자이고 각자 대화 기록이 있어서,
 * 누가 되든 채워진 화면을 본다. 방문자가 늘어도 사용자 목록이 무한정 늘지 않는다.
 *
 * <p>프로파일 {@code demo}에서만 뜬다. 비밀번호 없이 토큰을 내주므로 운영에 있으면 안 된다.
 */
@RestController
@Profile("demo & !prod")
@RequestMapping("/api/demo")
@RequiredArgsConstructor
public class DemoSessionController {

  private final UserRepository users;
  private final JwtTokenProvider jwtTokenProvider;

  /** 다음에 내줄 인물의 자리. 프로세스가 여럿이면 각자 돌지만 그래도 창마다 달라진다. */
  private final AtomicInteger cursor = new AtomicInteger();

  @PostMapping("/session")
  public ResponseEntity<AuthResponse> start() {
    int size = DemoDataConfig.PERSONAS.size();
    // 한 바퀴를 다 돌아도 못 찾으면 시드가 아직 안 끝난 것이다
    for (int attempt = 0; attempt < size; attempt++) {
      int index = Math.floorMod(cursor.getAndIncrement(), size);
      User persona = users.findByEmail(DemoDataConfig.PERSONAS.get(index)).orElse(null);
      if (persona == null) continue;
      String token = jwtTokenProvider.createToken(persona.getId(), persona.getEmail());
      return ResponseEntity.ok(
          new AuthResponse(token, persona.getId(), persona.getEmail(), persona.getNickname()));
    }
    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "데모 데이터를 준비하는 중입니다.");
  }
}
