package com.realtime.chat.bot;

import com.realtime.chat.domain.ChatRoom;
import com.realtime.chat.domain.MessageType;
import com.realtime.chat.domain.RoomType;
import com.realtime.chat.domain.User;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.producer.ChatMessageProducer;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.ChatRoomRepository;
import com.realtime.chat.repository.UserRepository;
import java.time.LocalDate;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 안내봇의 정기 공지.
 *
 * <p>하루 두 번, 참여 중인 그룹 방에 한 줄씩 남긴다. 실제 업무 메신저의 알림봇이 하는 일이다.
 *
 * <p><b>인스턴스가 2대라 타이머도 2벌 돈다.</b> 그대로 두면 공지가 두 번 올라간다.
 * {@code pg_advisory_xact_lock}으로 한 트랜잭션만 들어오게 막는다 —
 * {@code DemoDataConfig}가 데모 시드에서 쓰는 것과 같은 방식이다.
 *
 * <p>잠금을 잡은 뒤 <b>오늘 이미 보냈는지 다시 센다.</b> 잠금만으로는 재시작·재배포 때
 * 같은 날 두 번 도는 것을 막지 못한다.
 */
@Slf4j
@Component
@Profile("demo & !prod")
public class ChatBotScheduler {

  /** 데모 시드(8_270_101)와 겹치지 않는 임의의 값 */
  private static final long BOT_NOTICE_LOCK = 8_270_202L;

  private final UserRepository users;
  private final ChatRoomRepository rooms;
  private final ChatRoomMemberRepository members;
  private final ChatMessageProducer producer;
  private final JdbcTemplate jdbc;

  public ChatBotScheduler(
      UserRepository users,
      ChatRoomRepository rooms,
      ChatRoomMemberRepository members,
      ChatMessageProducer producer,
      JdbcTemplate jdbc) {
    this.users = users;
    this.rooms = rooms;
    this.members = members;
    this.producer = producer;
    this.jdbc = jdbc;
  }

  /** 스탠드업 알림 — 평일 09:30 */
  @Scheduled(cron = "0 30 9 * * MON-FRI", zone = "Asia/Seoul")
  public void standupReminder() {
    post("standup", "스탠드업 시작 10분 전입니다. 오늘 할 일을 한 줄로 남겨 주세요.");
  }

  /** 마감 정리 — 평일 18:00 */
  @Scheduled(cron = "0 0 18 * * MON-FRI", zone = "Asia/Seoul")
  public void wrapUpReminder() {
    post("wrapup", "오늘 하루 수고하셨습니다. 넘길 일이 있으면 이 방에 남겨 주세요.");
  }

  @Transactional
  void post(String kind, String text) {
    // 반환값이 없어 queryForObject로는 매핑되지 않는다. 결과를 버린다.
    jdbc.query("SELECT pg_advisory_xact_lock(?)", rs -> null, BOT_NOTICE_LOCK);

    Optional<User> bot = users.findByEmail(ChatBotRules.BOT_EMAIL);
    if (bot.isEmpty()) return;

    /*
     * 오늘 이 종류의 공지를 이미 보냈으면 건너뛴다.
     * 내용으로 세는 것은 투박하지만, 이걸 위해 표를 하나 더 만들 만한 일이 아니다.
     */
    Integer already =
        jdbc.queryForObject(
            """
            SELECT COUNT(*) FROM messages
            WHERE sender_id = ? AND content = ? AND created_at >= ?
            """,
            Integer.class,
            bot.get().getId(),
            text,
            LocalDate.now().atStartOfDay());
    if (already != null && already > 0) {
      log.debug("안내봇 {} 공지는 오늘 이미 보냈습니다", kind);
      return;
    }

    int sent = 0;
    for (ChatRoom room : rooms.findAll()) {
      if (room.getType() != RoomType.GROUP) continue;
      if (!members.existsByChatRoomIdAndUserId(room.getId(), bot.get().getId())) continue;
      producer.sendMessage(
          ChatMessageEvent.of(
              room.getId(), bot.get().getId(), bot.get().getNickname(), text, MessageType.TEXT));
      sent++;
    }
    log.info("안내봇 {} 공지 {}개 방에 발행", kind, sent);
  }
}
