package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentationGuardTest {

  private static final Path README = Path.of("README.md");

  @Test
  @DisplayName("README는 실제 제품 화면과 현재 메시지 생명주기 evidence 경계를 유지한다")
  void readmeKeepsProductStoryAndEvidenceBoundaries() throws IOException {
    String readme = Files.readString(README);

    assertThat(readme)
        .contains("![실제 Realtime Chat Alice와 Bob 대화 화면]")
        .contains("## 전환점: 저장되지 않은 메시지를 먼저 보여줄 수 있었다")
        .contains("## 오프라인과 재연결")
        .contains("## Redis publish 실패 뒤 복구")
        .doesNotContain("docs/assets/architecture/overall-architecture.svg");

    assertThat(readme)
        .contains("현재 성능 주장: 없음")
        .contains("historical unpinned archive")
        .contains("docker-compose.demo.yml -f docker-compose.e2e.yml")
        .doesNotContain(
            "937 -> 1,598",
            "212.85ms -> 149.22ms",
            "expected 99,900",
            "1,000-user receiver matrix repeat3에서");
  }
}
