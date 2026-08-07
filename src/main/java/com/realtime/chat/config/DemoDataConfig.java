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
   * 한 대화의 대본. 말하는 사람은 참여자를 순서대로 돌아가며 정한다.
   *
   * @param members 참여자 이메일 — 닉네임은 유일성이 보장되지 않는다
   */
  private record Script(String name, RoomType type, List<String> members, List<String> lines) {}

  private static final List<Script> SCRIPTS = List.of(
      new Script(null, RoomType.DIRECT, List.of("alice@demo.local", "bob@demo.local"), List.of(
          "내일 회의 자료 초안 올려뒀어요",
          "확인했어요. 3장만 다시 볼게요",
          "네, 그 부분만 고치면 될 것 같아요",
          "고친 버전 방금 올렸습니다",
          "좋아요. 이대로 가시죠")),
      new Script(null, RoomType.DIRECT, List.of("alice@demo.local", "yujin@demo.local"), List.of(
          "배포 시간 언제로 잡을까요?",
          "금요일 오후는 피하고 싶어요",
          "그럼 목요일 오전 어때요?",
          "좋습니다. 그때로 잡을게요")),
      new Script(null, RoomType.DIRECT, List.of("alice@demo.local", "dohyun@demo.local"), List.of(
          "로그에서 재시도가 계속 도는 게 보이는데 확인 부탁드려요",
          "네, 백오프가 2.5초라 그렇게 보일 수 있어요",
          "그럼 정상이네요. 감사합니다")),
      new Script("제품팀 스탠드업", RoomType.GROUP, List.of("alice@demo.local", "bob@demo.local", "semi@demo.local", "haram@demo.local"), List.of(
          "오늘 스탠드업 10분 뒤에 시작할게요",
          "저는 어제 작업 이어서 합니다",
          "저는 리뷰 두 건 남았어요",
          "설계 문서 초안 공유드렸습니다",
          "확인하고 코멘트 남길게요",
          "감사합니다")),
      new Script("금요일 로컬 배포", RoomType.GROUP, List.of("alice@demo.local", "dohyun@demo.local", "semi@demo.local"), List.of(
          "스테이징 올렸습니다",
          "확인했어요. 대기열 화면만 다시 볼게요",
          "저도 좌석표 쪽 보고 있습니다",
          "둘 다 문제 없으면 오후에 넘길게요")));

  @Bean
  ApplicationRunner seedDemoData(
      UserRepository users,
      ChatRoomRepository rooms,
      MessageRepository messages,
      ChatRoomMemberSeeder seeder,
      PasswordEncoder passwordEncoder) {
    return arguments -> {
      seedUser(users, passwordEncoder, "alice@demo.local", "Alice");
      seedUser(users, passwordEncoder, "bob@demo.local", "Bob");
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

        for (int i = 0; i < script.lines().size(); i++) {
          User sender = members.get(i % members.size());
          Message message = new Message(
              UUID.randomUUID(), room, sender, script.lines().get(i), MessageType.TEXT);
          messages.saveAndFlush(message);
          messageIds.add(message.getId());
          when.add(cursor.plusMinutes(i * 7L));
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
