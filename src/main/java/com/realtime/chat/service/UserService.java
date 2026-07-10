package com.realtime.chat.service;

import com.realtime.chat.common.BusinessException;
import com.realtime.chat.domain.User;
import com.realtime.chat.dto.UserSummaryResponse;
import com.realtime.chat.repository.UserRepository;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class UserService {

  private static final int MAX_SEARCH_RESULTS = 20;

  private final UserRepository userRepository;

  @Transactional(readOnly = true)
  public UserSummaryResponse getMe(Long userId) {
    return UserSummaryResponse.from(findUser(userId));
  }

  @Transactional(readOnly = true)
  public List<UserSummaryResponse> searchByNickname(Long userId, String nickname) {
    String query = nickname == null ? "" : nickname.trim();
    if (query.length() < 2) {
      throw new BusinessException(HttpStatus.BAD_REQUEST, "닉네임은 두 글자 이상 입력해 주세요.");
    }

    return userRepository.searchByNickname(userId, query, MAX_SEARCH_RESULTS).stream()
        .map(UserSummaryResponse::from)
        .toList();
  }

  private User findUser(Long userId) {
    return userRepository
        .findById(userId)
        .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
  }
}
