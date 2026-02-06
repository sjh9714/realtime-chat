package com.realtime.chat;

import com.realtime.chat.common.JwtTokenProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JwtTokenProviderTest {

    private JwtTokenProvider jwtTokenProvider;

    @BeforeEach
    void setUp() {
        jwtTokenProvider = new JwtTokenProvider(
                "test-jwt-secret-key-must-be-at-least-256-bits-long-for-hs256-algorithm",
                86400000L
        );
    }

    @Test
    @DisplayName("JWT 토큰 생성 및 userId 추출")
    void createAndExtractToken() {
        String token = jwtTokenProvider.createToken(1L, "test@test.com");

        assertThat(token).isNotBlank();
        assertThat(jwtTokenProvider.getUserId(token)).isEqualTo(1L);
    }

    @Test
    @DisplayName("유효한 토큰 검증 성공")
    void validateValidToken() {
        String token = jwtTokenProvider.createToken(1L, "test@test.com");

        assertThat(jwtTokenProvider.validateToken(token)).isTrue();
    }

    @Test
    @DisplayName("유효하지 않은 토큰 검증 실패")
    void validateInvalidToken() {
        assertThat(jwtTokenProvider.validateToken("invalid.token.here")).isFalse();
    }

    @Test
    @DisplayName("만료된 토큰 검증 실패")
    void validateExpiredToken() {
        JwtTokenProvider expiredProvider = new JwtTokenProvider(
                "test-jwt-secret-key-must-be-at-least-256-bits-long-for-hs256-algorithm",
                0L // 즉시 만료
        );
        String token = expiredProvider.createToken(1L, "test@test.com");

        assertThat(expiredProvider.validateToken(token)).isFalse();
    }
}
