package com.realtime.chat.bot;

import com.realtime.chat.config.KafkaConfig;
import com.realtime.chat.domain.MessageType;
import com.realtime.chat.domain.User;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.producer.ChatMessageProducer;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.UserRepository;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * 안내봇의 귀.
 *
 * <p>사람이 보낸 메시지를 듣고 있다가 자기를 부르면 답한다.
 *
 * <p><b>답할 때도 파이프라인을 우회하지 않는다.</b> 소켓에 바로 찔러 넣지 않고
 * 사람과 똑같이 {@link ChatMessageProducer}로 보낸다. Kafka → DB 커밋 → 브로드캐스트.
 * 그래야 봇의 말도 "저장된 뒤에만 전달된다"는 이 서비스의 약속 안에 들어온다.
 *
 * <p>저장 컨슈머와 <b>다른 그룹</b>으로 붙는다({@code chat-bot}). 여기서 무슨 일이 나도
 * 메시지 저장과 전달은 영향을 받지 않는다.
 */
@Slf4j
@Component
@Profile("demo & !prod")
@RequiredArgsConstructor
public class ChatBotListener {

  private final UserRepository users;
  private final ChatRoomMemberRepository members;
  private final ChatMessageProducer producer;

  @KafkaListener(topics = KafkaConfig.MESSAGES_TOPIC, containerFactory = "chatBotListenerFactory")
  public void onMessage(ConsumerRecord<String, ChatMessageEvent> record, Acknowledgment ack) {
    /*
     * 무슨 일이 나도 오프셋은 넘긴다.
     *
     * 봇이 답을 못 했다고 같은 메시지를 계속 다시 읽으면, 고쳐지지 않는 한 멈춰 서고
     * 결국 사람의 대화까지 방해한다. 봇은 있으면 좋은 것이지 보장 대상이 아니다.
     */
    try {
      reply(record.value());
    } catch (Exception e) {
      log.warn("안내봇 응답 실패: 넘어간다: {}", e.toString());
    } finally {
      ack.acknowledge();
    }
  }

  private void reply(ChatMessageEvent event) {
    if (event == null) return;

    Optional<User> bot = users.findByEmail(ChatBotRules.BOT_EMAIL);
    if (bot.isEmpty()) return;
    // 자기 말에 자기가 답하면 끝없이 주고받는다
    if (bot.get().getId().equals(event.getSenderId())) return;

    boolean senderIsBot = users.findById(event.getSenderId()).map(User::isBot).orElse(false);
    Optional<String> answer = ChatBotRules.replyTo(event.getContent(), senderIsBot);
    if (answer.isEmpty()) return;

    // 봇이 참여하지 않은 방에는 말하지 않는다. 사람이 지켜야 하는 규칙을 봇도 지킨다.
    if (!members.existsByChatRoomIdAndUserId(event.getRoomId(), bot.get().getId())) return;

    producer.sendMessage(
        ChatMessageEvent.of(
            event.getRoomId(),
            bot.get().getId(),
            bot.get().getNickname(),
            answer.get(),
            MessageType.TEXT));
    log.debug("안내봇 응답: roomId={}", event.getRoomId());
  }
}
