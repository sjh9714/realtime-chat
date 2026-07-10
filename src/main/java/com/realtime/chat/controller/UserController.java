package com.realtime.chat.controller;

import com.realtime.chat.dto.UserSummaryResponse;
import com.realtime.chat.service.UserService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

  private final UserService userService;

  @GetMapping("/me")
  public ResponseEntity<UserSummaryResponse> getMe(@AuthenticationPrincipal Long userId) {
    return ResponseEntity.ok(userService.getMe(userId));
  }

  @GetMapping("/search")
  public ResponseEntity<List<UserSummaryResponse>> search(
      @AuthenticationPrincipal Long userId, @RequestParam String nickname) {
    return ResponseEntity.ok(userService.searchByNickname(userId, nickname));
  }
}
