package com.realtime.chat.config;

import com.realtime.chat.domain.User;
import com.realtime.chat.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

@Slf4j
@Configuration
@Profile("demo & !prod")
public class DemoDataConfig {

  public static final String DEMO_PASSWORD = "demo-password";

  @Bean
  ApplicationRunner seedDemoUsers(UserRepository users, PasswordEncoder passwordEncoder) {
    return arguments -> {
      seed(users, passwordEncoder, "alice@demo.local", "Alice");
      seed(users, passwordEncoder, "bob@demo.local", "Bob");
    };
  }

  private void seed(
      UserRepository users, PasswordEncoder encoder, String email, String nickname) {
    if (users.existsByEmail(email)) return;
    try {
      users.saveAndFlush(new User(email, encoder.encode(DEMO_PASSWORD), nickname));
    } catch (DataIntegrityViolationException ignored) {
      log.debug("다른 app instance가 demo user를 먼저 생성했습니다: {}", email);
    }
  }
}
