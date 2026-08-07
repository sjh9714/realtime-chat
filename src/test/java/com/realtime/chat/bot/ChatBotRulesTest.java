package com.realtime.chat.bot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * 안내봇이 <b>언제 입을 다무는지</b>가 이 테스트의 핵심이다.
 *
 * <p>아무 말에나 답하면 방문자가 떠난 뒤에도 데모 데이터가 계속 불어난다.
 * 사람이 먼저 부를 때만 움직여야 한다.
 */
class ChatBotRulesTest {

  @Test
  @DisplayName("평범한 대화에는 끼어들지 않는다")
  void staysQuietOnOrdinaryTalk() {
    assertThat(ChatBotRules.replyTo("오늘 배포 언제 하나요?", false)).isEmpty();
    assertThat(ChatBotRules.replyTo("확인했습니다", false)).isEmpty();
    assertThat(ChatBotRules.replyTo("", false)).isEmpty();
    assertThat(ChatBotRules.replyTo("   ", false)).isEmpty();
    assertThat(ChatBotRules.replyTo(null, false)).isEmpty();
  }

  @Test
  @DisplayName("봇이 보낸 말에는 답하지 않는다 — 서로 끝없이 주고받는 것을 막는다")
  void neverRepliesToABot() {
    assertThat(ChatBotRules.replyTo("@안내봇 도움", true)).isEmpty();
    assertThat(ChatBotRules.replyTo("/도움", true)).isEmpty();
  }

  @Test
  @DisplayName("멘션하면 답한다")
  void repliesWhenMentioned() {
    assertThat(ChatBotRules.replyTo("@안내봇 도움", false)).isPresent();
    assertThat(ChatBotRules.replyTo("여기 @안내봇 있나요", false)).isPresent();
  }

  @Test
  @DisplayName("슬래시 명령에 답한다")
  void repliesToCommands() {
    assertThat(ChatBotRules.replyTo("/도움", false)).get().asString().contains("/상태");
    assertThat(ChatBotRules.replyTo("/전달", false)).get().asString().contains("전달 완료");
    assertThat(ChatBotRules.replyTo("/초대", false)).get().asString().contains("초대 링크");
  }

  @Test
  @DisplayName("모르는 말에는 모른다고 하고 할 수 있는 것을 알려 준다")
  void admitsWhatItDoesNotKnow() {
    assertThat(ChatBotRules.replyTo("/날씨", false)).get().asString().contains("아직 모르는 말");
  }

  @Test
  @DisplayName("멘션만 하고 용건이 없으면 할 수 있는 것을 알려 준다")
  void bareMentionShowsHelp() {
    assertThat(ChatBotRules.replyTo("@안내봇", false)).get().asString().contains("/상태");
  }
}
