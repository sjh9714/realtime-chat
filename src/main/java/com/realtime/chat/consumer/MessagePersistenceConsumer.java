package com.realtime.chat.consumer;

import com.realtime.chat.common.BusinessException;
import com.realtime.chat.config.KafkaConfig;
import com.realtime.chat.domain.ChatRoom;
import com.realtime.chat.domain.Message;
import com.realtime.chat.domain.User;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.ChatRoomRepository;
import com.realtime.chat.repository.MessageRepository;
import com.realtime.chat.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.cache.CacheManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// Consumer Group 1: 메시지를 DB에 저장 + 멱등성 체크 + unreadCount 증가
@Slf4j
@Component
@RequiredArgsConstructor
public class MessagePersistenceConsumer {

  private final MessageRepository messageRepository;
  private final ChatRoomRepository chatRoomRepository;
  private final UserRepository userRepository;
  private final ChatRoomMemberRepository chatRoomMemberRepository;
  private final Counter messagesPersistedCounter;
  private final Counter messagesFailedCounter;
  private final Timer messagesLatencyTimer;
  private final CacheManager cacheManager;

  @KafkaListener(
      topics = KafkaConfig.MESSAGES_TOPIC,
      containerFactory = "persistenceListenerFactory")
  @Transactional
  public void consume(ConsumerRecord<String, ChatMessageEvent> record, Acknowledgment ack) {
    ChatMessageEvent event = record.value();
    log.debug(
        "메시지 수신 (persistence): messageKey={}, roomId={}", event.getMessageKey(), event.getRoomId());

    try {
      // 멱등성 체크: 동일 messageKey가 이미 저장되어 있으면 스킵
      if (messageRepository.existsByMessageKey(event.getMessageKey())) {
        log.info("중복 메시지 스킵: messageKey={}", event.getMessageKey());
        ack.acknowledge();
        return;
      }
      if (isDuplicateClientMessage(event)) {
        log.info(
            "중복 클라이언트 메시지 스킵: senderId={}, clientMessageId={}",
            event.getSenderId(),
            event.getClientMessageId());
        ack.acknowledge();
        return;
      }

      ChatRoom room =
          chatRoomRepository
              .findById(event.getRoomId())
              .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."));
      User sender =
          userRepository
              .findById(event.getSenderId())
              .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

      Message message =
          new Message(
              event.getMessageKey(),
              event.getClientMessageId(),
              room,
              sender,
              event.getContent(),
              event.getType());
      message.updateKafkaMetadata(record.partition(), record.offset());
      try {
        messageRepository.saveAndFlush(message);
      } catch (DataIntegrityViolationException e) {
        if (isDuplicateMessage(event)) {
          log.info(
              "중복 메시지 unique 충돌 스킵: messageKey={}, senderId={}, clientMessageId={}",
              event.getMessageKey(),
              event.getSenderId(),
              event.getClientMessageId());
          ack.acknowledge();
          return;
        }
        throw e;
      }

      // 발신자를 제외한 멤버들의 unreadCount 증가 + 해당 방 멤버 캐시만 무효화
      chatRoomMemberRepository.incrementUnreadCountForOtherMembers(
          event.getRoomId(), event.getSenderId());
      var roomsCache = cacheManager.getCache("rooms");
      if (roomsCache != null) {
        chatRoomMemberRepository.findUserIdsByRoomId(event.getRoomId()).forEach(roomsCache::evict);
      }

      // 메트릭: 저장 성공 + 지연시간
      messagesPersistedCounter.increment();
      Duration latency = Duration.between(event.getTimestamp(), LocalDateTime.now());
      messagesLatencyTimer.record(latency);

      ack.acknowledge();
      log.debug(
          "메시지 저장 완료: messageKey={}, id={}, latency={}ms",
          event.getMessageKey(),
          message.getId(),
          latency.toMillis());

    } catch (Exception e) {
      messagesFailedCounter.increment();
      log.error(
          "메시지 저장 실패: messageKey={}, topic={}, partition={}, offset={}",
          event.getMessageKey(),
          record.topic(),
          record.partition(),
          record.offset(),
          e);
      throw e; // ErrorHandler가 재시도 후 DLT로 보냄
    }
  }

  private boolean isDuplicateMessage(ChatMessageEvent event) {
    return messageRepository.existsByMessageKey(event.getMessageKey())
        || isDuplicateClientMessage(event);
  }

  private boolean isDuplicateClientMessage(ChatMessageEvent event) {
    UUID clientMessageId = event.getClientMessageId();
    return clientMessageId != null
        && messageRepository.existsBySenderIdAndClientMessageId(
            event.getSenderId(), clientMessageId);
  }
}
