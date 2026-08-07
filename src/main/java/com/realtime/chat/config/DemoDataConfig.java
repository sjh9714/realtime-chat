package com.realtime.chat.config;

import com.realtime.chat.domain.ChatRoom;
import com.realtime.chat.domain.Message;
import com.realtime.chat.domain.MessageType;
import com.realtime.chat.domain.RoomType;
import com.realtime.chat.domain.User;
import com.realtime.chat.repository.ChatRoomRepository;
import com.realtime.chat.repository.MessageRepository;
import com.realtime.chat.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 데모 데이터.
 *
 * <p>전에는 사용자 둘만 만들고 대화는 없었다. 그래서 데모를 열면 빈 화면이었고,
 * 시연하려면 그 자리에서 대화를 만들어 세 마디를 주고받아야 했다.
 * 그렇게 찍은 화면은 메신저가 아니라 기능 테스트로 보인다.
 *
 * <p>쓰던 메신저처럼 보이려면 <b>대화가 여럿이고 각각 과거가 있어야 한다.</b>
 * 1:1과 그룹을 섞고, 며칠에 걸친 기록을 넣는다.
 *
 * <p>메시지의 {@code createdAt}은 {@code @PrePersist}가 현재 시각으로 채우므로
 * 엔티티로는 과거를 만들 수 없다. 저장한 뒤 SQL로 시각을 옮긴다.
 */
@Slf4j
@Configuration
@Profile("demo & !prod")
public class DemoDataConfig {

  public static final String DEMO_PASSWORD = "demo-password";

  /** 데모 시드용 자문 잠금 키. 다른 용도와 겹치지 않는 임의의 값이면 된다. */
  private static final long DEMO_SEED_LOCK = 8_270_101L;

  /**
   * 대본의 한 줄. {@code speaker}는 {@link Script#members()}의 자리 번호다.
   *
   * <p>전에는 말하는 사람을 참여자 순서대로 돌아가며 정했다. 그래서 한 사람이 두 번 이어
   * 말하는 일이 한 번도 없었고, <b>화면의 연속 메시지 묶기가 한 번도 발동하지 않았다.</b>
   * 실제 대화는 그렇지 않다 — 한 사람이 짧은 말을 이어 붙인다.
   */
  private record Line(int speaker, String text) {}

  /**
   * 한 대화의 대본.
   *
   * @param members 참여자 이메일 — 닉네임은 유일성이 보장되지 않는다
   */
  private record Script(String name, RoomType type, List<String> members, List<Line> lines) {}

  /**
   * 데모 인물. 이메일이 곧 열쇠다.
   *
   * <p><b>이 목록이 곧 데모 진입에서 내주는 사람들이다</b>({@code DemoSessionController}).
   * 창을 두 개 열면 서로 다른 사람이 되어 실제로 대화가 오간다.
   *
   * <p>전에는 Alice·Bob이 섞여 있었다. 교과서 placeholder 이름이라 읽는 순간
   * 소품인 것이 보였다.
   */
  public static final List<String> PERSONAS = List.of(
      "jiwon@demo.local",
      "taeho@demo.local",
      "yujin@demo.local",
      "dohyun@demo.local",
      "semi@demo.local",
      "haram@demo.local");

  /*
   * 그룹 방에는 여섯 명이 모두 들어간다.
   *
   * 데모 진입이 인물을 돌아가며 내주므로, 누가 되든 방이 채워져 있어야 한다.
   * 한두 사람만 넣으면 어떤 사람으로 들어왔을 때 목록이 비어 버린다.
   */
  private static final List<String> EVERYONE = PERSONAS;

  private static final List<Script> SCRIPTS = List.of(
      new Script(null, RoomType.DIRECT, List.of("jiwon@demo.local", "taeho@demo.local"), List.of(
          new Line(0, "내일 회의 자료 초안 올려뒀어요"),
          new Line(0, "3장이 좀 기니까 거기부터 봐 주세요"),
          new Line(1, "확인했어요. 3장만 다시 볼게요"),
          new Line(0, "네, 그 부분만 고치면 될 것 같아요"),
          new Line(1, "고친 버전 방금 올렸습니다"),
          new Line(1, "표도 하나 줄였어요"),
          new Line(0, "좋아요. 이대로 가시죠"))),
      new Script(null, RoomType.DIRECT, List.of("yujin@demo.local", "dohyun@demo.local"), List.of(
          new Line(0, "아까 말한 문서 어디 있어요?"),
          new Line(1, "공유 폴더 안에 넣어 뒀어요"),
          new Line(1, "이름이 좀 길어서 검색하면 바로 나옵니다"),
          new Line(0, "찾았어요. 고마워요"))),
      new Script(null, RoomType.DIRECT, List.of("semi@demo.local", "haram@demo.local"), List.of(
          new Line(0, "오늘 몇 시에 퇴근하세요?"),
          new Line(1, "여섯 시 조금 넘어서요"),
          new Line(0, "그럼 같이 나가요"),
          new Line(1, "좋아요"))),
      new Script(null, RoomType.DIRECT, List.of("jiwon@demo.local", "semi@demo.local"), List.of(
          new Line(1, "내일 오전에 잠깐 시간 되세요?"),
          new Line(0, "열한 시 이후면 괜찮아요"),
          new Line(1, "그럼 열한 시로 잡을게요"),
          new Line(1, "삼십 분이면 충분할 것 같습니다"),
          new Line(0, "네 그때 봬요"))),
      new Script("제품팀 스탠드업", RoomType.GROUP, EVERYONE, List.of(
          new Line(0, "오늘 스탠드업 10분 뒤에 시작할게요"),
          new Line(1, "저는 어제 작업 이어서 합니다"),
          new Line(1, "오전에 끝날 것 같아요"),
          new Line(4, "저는 리뷰 두 건 남았어요"),
          new Line(4, "오후에는 다른 일 볼 수 있습니다"),
          new Line(5, "설계 문서 초안 공유드렸습니다"),
          new Line(5, "의견 주시면 이번 주 안에 반영할게요"),
          new Line(2, "저는 어제 올린 것 확인만 하면 됩니다"),
          new Line(3, "저도 특이사항 없습니다"),
          new Line(0, "확인하고 코멘트 남길게요"),
          new Line(0, "다들 고생하셨습니다"),
          new Line(5, "감사합니다"))),
      new Script("배포 준비", RoomType.GROUP, EVERYONE, List.of(
          new Line(3, "스테이징 올렸습니다"),
          new Line(3, "한 번씩 눌러 봐 주세요"),
          new Line(0, "확인했어요. 목록 화면만 다시 볼게요"),
          new Line(4, "저도 지금 보고 있습니다"),
          new Line(4, "지금까지는 특이사항 없어요"),
          new Line(1, "저는 로그만 잠깐 봤는데 깨끗합니다"),
          new Line(0, "둘 다 문제 없으면 오후에 넘길게요"),
          new Line(2, "네 그때 맞춰서 공지 준비할게요"),
          new Line(5, "확인했습니다"),
          new Line(0, "고맙습니다"))),
      new Script("디자인 리뷰", RoomType.GROUP, EVERYONE, List.of(
          new Line(5, "시안 두 개 올렸습니다"),
          new Line(5, "A는 여백을 넉넉히, B는 정보를 더 담았어요"),
          new Line(2, "저는 B가 좋습니다"),
          new Line(2, "한 번에 보이는 게 많아서요"),
          new Line(4, "저도 B요. 다만 글자가 조금 작아 보여요"),
          new Line(0, "그건 키우면 될 것 같아요"),
          new Line(1, "저는 A가 편했는데, 다수가 B면 따르겠습니다"),
          new Line(5, "그럼 B로 가고 글자만 한 단계 키우겠습니다"),
          new Line(3, "좋습니다"),
          new Line(0, "정리 고맙습니다"))),
      new Script("점심 뭐 먹지", RoomType.GROUP, EVERYONE, List.of(
          new Line(2, "오늘 점심 뭐 드실래요"),
          new Line(4, "저는 아무거나 좋아요"),
          new Line(1, "어제 국수 먹었으니까 오늘은 밥이요"),
          new Line(3, "1층에 새로 생긴 데 어때요"),
          new Line(3, "웨이팅이 좀 있다던데 지금 가면 괜찮을 것 같아요"),
          new Line(5, "저 거기 가 봤는데 괜찮았어요"),
          new Line(0, "그럼 거기로 가죠"),
          new Line(2, "열두 시에 로비에서 봬요"),
          new Line(4, "네"),
          new Line(1, "곧 내려갈게요"))));

  @Bean
  ApplicationRunner seedDemoData(
      UserRepository users,
      ChatRoomRepository rooms,
      MessageRepository messages,
      ChatRoomMemberSeeder seeder,
      PasswordEncoder passwordEncoder) {
    return arguments -> {
      seedUser(users, passwordEncoder, "jiwon@demo.local", "지원");
      seedUser(users, passwordEncoder, "taeho@demo.local", "태호");
      seedUser(users, passwordEncoder, "yujin@demo.local", "유진");
      seedUser(users, passwordEncoder, "dohyun@demo.local", "도현");
      seedUser(users, passwordEncoder, "semi@demo.local", "세미");
      seedUser(users, passwordEncoder, "haram@demo.local", "하람");
      seeder.seedConversations(users, rooms, messages);
    };
  }

  private void seedUser(
      UserRepository users, PasswordEncoder encoder, String email, String nickname) {
    if (users.existsByEmail(email)) return;
    try {
      users.saveAndFlush(new User(email, encoder.encode(DEMO_PASSWORD), nickname));
    } catch (DataIntegrityViolationException ignored) {
      log.debug("다른 app instance가 demo user를 먼저 생성했습니다: {}", email);
    }
  }

  /**
   * 대화 시드. 트랜잭션이 필요해 별도 빈으로 둔다 —
   * 같은 클래스 안에서 부르면 {@code @Transactional}이 걸리지 않는다.
   */
  @Bean
  ChatRoomMemberSeeder chatRoomMemberSeeder(JdbcTemplate jdbc) {
    return new ChatRoomMemberSeeder(jdbc);
  }

  static class ChatRoomMemberSeeder {

    private final JdbcTemplate jdbc;

    ChatRoomMemberSeeder(JdbcTemplate jdbc) {
      this.jdbc = jdbc;
    }

    @Transactional
    void seedConversations(
        UserRepository users, ChatRoomRepository rooms, MessageRepository messages) {
      /*
       * app instance가 둘이라 둘 다 이 코드를 돈다. count()만 보고 판단하면
       * 둘 다 0을 보고 둘 다 시드해서 방이 두 벌 생긴다 — 실제로 그렇게 됐다.
       *
       * 트랜잭션 자문 잠금으로 한 번에 하나만 들어오게 하고, 잠금을 잡은 뒤에 다시 센다.
       * 잠금은 트랜잭션이 끝나면 풀린다.
       */
      // 반환값이 void라 queryForObject로는 매핑되지 않는다. 결과를 버린다.
      jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, DEMO_SEED_LOCK);
      if (rooms.count() > 0) return;

      List<Long> messageIds = new ArrayList<>();
      List<LocalDateTime> when = new ArrayList<>();

      /*
       * 가장 오래된 대화부터. 마지막 대화가 목록 맨 위에 오도록 시각을 배치한다.
       *
       * 시각을 업무 시간대(09:20)로 고정한다. now() 기준으로 두면 컨테이너를 새벽에
       * 띄웠을 때 모든 대화가 "오전 03시"에 오간 것이 되어 쓰던 메신저로 보이지 않는다.
       */
      LocalDateTime cursor = LocalDate.now()
          .minusDays(SCRIPTS.size())
          .atTime(9, 20);

      for (Script script : SCRIPTS) {
        // 닉네임은 유일성이 보장되지 않으므로 이메일로 찾는다
        List<User> members = script.members().stream()
            .map(email -> users.findByEmail(email).orElseThrow())
            .toList();

        ChatRoom room = new ChatRoom(
            script.type() == RoomType.GROUP ? script.name() : null,
            script.type(),
            members.get(0));
        members.forEach(room::addMember);
        rooms.saveAndFlush(room);

        /*
         * 방과 참여 시각도 함께 과거로 옮긴다.
         *
         * 메시지만 과거로 보내면 "참여 이전 메시지는 읽음 기준으로 사용할 수 없습니다"로
         * 읽음 처리가 400이 된다. 앱의 규칙이 맞다 — 들어오기 전 메시지를 읽었다고
         * 표시할 수는 없다. 시드가 만든 시간이 앞뒤가 안 맞았던 것이다.
         */
        LocalDateTime opened = cursor.minusMinutes(5);
        jdbc.update("UPDATE chat_rooms SET created_at = ? WHERE id = ?", opened, room.getId());
        jdbc.update("UPDATE chat_room_members SET joined_at = ? WHERE room_id = ?", opened, room.getId());

        /*
         * 이어 말하는 줄은 1분 뒤에 둔다. 화면이 같은 사람의 연속 메시지를 5분 안일 때만
         * 묶으므로(`Conversation.tsx`), 7분씩 벌리면 묶이지 않는다.
         * 사람이 바뀔 때만 7분을 띄운다.
         */
        LocalDateTime at = cursor;
        for (int i = 0; i < script.lines().size(); i++) {
          Line line = script.lines().get(i);
          if (i > 0) {
            boolean sameSpeaker = script.lines().get(i - 1).speaker() == line.speaker();
            at = at.plusMinutes(sameSpeaker ? 1L : 7L);
          }
          Message message = new Message(
              UUID.randomUUID(), room, members.get(line.speaker()), line.text(), MessageType.TEXT);
          messages.saveAndFlush(message);
          messageIds.add(message.getId());
          when.add(at);
        }
        cursor = cursor.plusDays(1);
      }

      // @PrePersist가 현재 시각으로 채운 것을 대본의 시각으로 옮긴다.
      // 하루 안에 다 몰려 있으면 '쓰던 대화'로 보이지 않는다.
      for (int i = 0; i < messageIds.size(); i++) {
        jdbc.update("UPDATE messages SET created_at = ? WHERE id = ?", when.get(i), messageIds.get(i));
      }

      log.info("데모 대화 {}개 · 메시지 {}건 생성", SCRIPTS.size(), messageIds.size());
    }
  }
}
