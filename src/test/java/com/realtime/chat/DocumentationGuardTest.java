package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentationGuardTest {

  private static final Path README = Path.of("README.md");
  private static final Path ARCHITECTURE_ASSET_DIR =
      Path.of("docs", "assets", "architecture");

  @Test
  @DisplayName("README는 전체 아키텍처와 현재 코드 evidence 경계를 유지한다")
  void readmeKeepsArchitectureAndEvidenceBoundaries() throws IOException {
    String readme = Files.readString(README);

    assertThat(readme).contains("## 전체 아키텍처");
    assertThat(readme)
        .contains(
            "![Realtime Chat 전체 아키텍처](docs/assets/architecture/overall-architecture.svg)");
    assertThat(readme).contains("### 핵심 설계 판단");
    assertThat(readme)
        .contains(
            "이 다이어그램은 구현된 핵심 흐름과 검증 대상 경계를 설명하기 위한 단순화된 구조도이며, 운영 배포 토폴로지나 production SLO를 주장하지 않습니다.");

    assertThat(readme)
        .contains("현재 성능 주장: 없음")
        .contains("historical unpinned archive")
        .contains("docker-compose.demo.yml -f docker-compose.e2e.yml")
        .contains("`x-app-instance`")
        .doesNotContain(
            "937 -> 1,598",
            "212.85ms -> 149.22ms",
            "expected 99,900",
            "1,000-user receiver matrix repeat3에서");
  }

  @Test
  @DisplayName("아키텍처 asset은 SVG/drawio만 사용한다")
  void architectureAssetsDoNotUseRasterImages() throws IOException {
    assertThat(ARCHITECTURE_ASSET_DIR.resolve("overall-architecture.svg")).exists();
    assertThat(ARCHITECTURE_ASSET_DIR.resolve("overall-architecture.drawio")).exists();

    try (Stream<Path> paths = Files.walk(ARCHITECTURE_ASSET_DIR)) {
      assertThat(paths.filter(Files::isRegularFile).map(DocumentationGuardTest::extension))
          .containsOnly("svg", "drawio")
          .doesNotContain("png", "jpg", "jpeg", "webp");
    }
  }

  private static String extension(Path path) {
    String filename = path.getFileName().toString().toLowerCase(Locale.ROOT);
    int dotIndex = filename.lastIndexOf('.');
    return dotIndex == -1 ? "" : filename.substring(dotIndex + 1);
  }
}
