package com.realtime.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class DocumentationGuardTest {

  private static final Path README = Path.of("README.md");
  private static final Path ARCHITECTURE_ASSETS = Path.of("docs", "assets", "architecture");

  @Test
  @DisplayName("README는 서비스를 소개하고 개발 문서는 메시지 처리와 검증 범위를 설명한다")
  void readmeKeepsProductStoryAndEvidenceBoundaries() throws IOException {
    String readme = Files.readString(README);

    assertThat(readme)
        .contains("docs/assets/screens/demo-desktop.png")
        .contains("docs/VERIFICATION.md")
        .contains("docs/SERVICE_GUIDE.md")
        .contains("서버 저장 완료")
        .doesNotContain("docs/assets/architecture/overall-architecture.svg");

    assertThat(Files.readString(Path.of("docs/SERVICE_GUIDE.md")))
        .contains("assets/architecture/request-flow.svg", "그림 설명:")
        .contains("historyCursorByRoom", "실시간 메시지와 저장 ACK는 이 기준을 변경하지 않습니다");

    assertThat(Path.of("docs/assets/screens/demo-desktop.png")).isRegularFile();
    assertThat(Files.readString(ARCHITECTURE_ASSETS.resolve("request-flow.svg")))
        .contains("<title", "<desc", "PERSISTED", "화면 수신");

    assertThat(Files.readString(Path.of("docs/ARCHITECTURE.md")))
        .contains("현재 성능 주장: 없음")
        .contains("historical unpinned archive");
    assertThat(Files.readString(Path.of("docs/TESTING.md")))
        .contains("docker-compose.demo.yml -f docker-compose.e2e.yml");
    assertThat(readme).doesNotContain(
            "937 -> 1,598",
            "212.85ms -> 149.22ms",
            "expected 99,900",
            "1,000-user receiver matrix repeat3에서");
  }

  @Test
  @DisplayName("메시지 생명주기 그림은 편집 원본과 2x PNG를 함께 유지한다")
  void focusedDiagramKeepsEditableSourceAndTwoTimesExport() throws IOException {
    Path drawio = ARCHITECTURE_ASSETS.resolve("persist-before-broadcast.drawio");
    Path png = ARCHITECTURE_ASSETS.resolve("persist-before-broadcast.png");

    assertThat(drawio).isRegularFile();
    assertThat(png).isRegularFile();

    byte[] pngBytes = Files.readAllBytes(png);
    int width = ByteBuffer.wrap(pngBytes, 16, 8).getInt();
    int height = ByteBuffer.wrap(pngBytes, 20, 4).getInt();
    assertThat(width).isGreaterThanOrEqualTo(2400);
    assertThat(height).isGreaterThanOrEqualTo(1000);

    assertThat(Files.readString(drawio))
        .contains(
            "old-broadcast",
            "old-visible",
            "old-persist",
            "new-persist",
            "new-broadcast",
            "new-visible");
  }
}
