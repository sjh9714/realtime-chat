package com.realtime.chat.bot;

import java.util.List;
import java.util.Optional;

/**
 * 안내봇이 무엇에 답할지 정하는 규칙.
 *
 * <p>순수 함수다 — 들어온 문장 하나로 답이 정해진다. 그래서 단위 테스트로 고정할 수 있고,
 * e2e에서도 매번 같은 답이 나온다. LLM을 붙이지 않은 이유가 이것이다(키도 비용도 없고,
 * 답이 흔들리면 캡처와 테스트가 같이 흔들린다).
 *
 * <p><b>좁게 답한다.</b> 멘션하거나 명령을 쳤을 때만 답한다. 아무 말에나 답하면
 * 방문자가 떠난 뒤에도 데모 데이터가 계속 불어난다.
 */
public final class ChatBotRules {

  public static final String BOT_EMAIL = "bot@demo.local";
  public static final String BOT_NICKNAME = "안내봇";

  /** 멘션으로 인정하는 표기 */
  private static final List<String> MENTIONS = List.of("@" + BOT_NICKNAME, "@bot", "@안내");

  private ChatBotRules() {}

  /**
   * 답할 말을 정한다. 답하지 않아야 하면 비어 있다.
   *
   * @param senderIsBot 보낸 이가 봇이면 답하지 않는다 — 봇끼리 무한히 주고받는 것을 막는다
   */
  public static Optional<String> replyTo(String content, boolean senderIsBot) {
    if (senderIsBot) return Optional.empty();
    if (content == null) return Optional.empty();

    String text = content.trim();
    if (text.isEmpty()) return Optional.empty();

    boolean mentioned = MENTIONS.stream().anyMatch(text::contains);
    boolean command = text.startsWith("/");
    if (!mentioned && !command) return Optional.empty();

    // 멘션 표기를 걷어낸 나머지가 실제 용건이다
    String body = text;
    for (String mention : MENTIONS) {
      body = body.replace(mention, " ");
    }
    body = body.replaceFirst("^/", "").trim();

    return Optional.of(answer(body));
  }

  private static String answer(String body) {
    String q = body.toLowerCase();

    if (q.isEmpty() || q.contains("도움") || q.startsWith("help") || q.contains("뭐 할 수")) {
      return """
          이런 것들을 물어볼 수 있어요.
          · /상태 — 지금 이 방의 참여자와 연결 상태
          · /전달 — 메시지가 어떤 단계를 거쳐 도착하는지
          · /초대 — 다른 사람을 이 대화로 부르는 방법""";
    }
    if (q.contains("상태") || q.startsWith("status")) {
      return "이 방의 참여자는 위 목록에서 확인할 수 있어요. 접속 중인 사람 수는 방 제목 아래에 표시됩니다.";
    }
    if (q.contains("전달") || q.contains("저장") || q.startsWith("delivery")) {
      return "보낸 메시지는 '보내는 중 → 서버 접수 → 서버 저장 완료' 순으로 바뀝니다. 서버 저장 완료는 DB에 남았다는 뜻이며 상대방의 수신이나 읽음 확인은 아닙니다.";
    }
    if (q.contains("초대") || q.startsWith("invite")) {
      return "위쪽 '초대 링크 복사'를 눌러 링크를 보내면 상대가 이 대화에 참여합니다. 창을 하나 더 열어 링크를 붙여 넣으면 혼자서도 확인할 수 있어요.";
    }
    if (q.contains("안녕") || q.startsWith("hi") || q.startsWith("hello")) {
      return "안녕하세요. 궁금한 게 있으면 /도움 이라고 불러 주세요.";
    }

    return "아직 모르는 말이에요. /도움 이라고 하면 할 수 있는 것을 알려드릴게요.";
  }
}
