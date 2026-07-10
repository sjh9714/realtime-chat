package com.realtime.chat.service;

import com.realtime.chat.common.BusinessException;
import com.realtime.chat.domain.ChatRoom;
import com.realtime.chat.domain.Message;
import com.realtime.chat.domain.User;
import com.realtime.chat.dto.MessageResponse;
import com.realtime.chat.event.ChatMessageEvent;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.ChatRoomRepository;
import com.realtime.chat.repository.MessageRepository;
import com.realtime.chat.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.CacheManager;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Slf4j
@Service
@RequiredArgsConstructor
public class MessagePersistenceService {

  private final MessageRepository messageRepository;
  private final ChatRoomRepository chatRoomRepository;
  private final UserRepository userRepository;
  private final ChatRoomMemberRepository chatRoomMemberRepository;
  @Qualifier("messagesPersistedCounter")
  private final Counter messagesPersistedCounter;
  @Qualifier("messagesLatencyTimer")
  private final Timer messagesLatencyTimer;
  @Qualifier("roomsCacheEvictionsCounter")
  private final Counter roomsCacheEvictionsCounter;
  private final CacheManager cacheManager;
  private final PersistenceFailureProbe failureProbe;

  @Transactional
  public PersistedMessageResult persist(
      ChatMessageEvent event, int kafkaPartition, long kafkaOffset) {
    failureProbe.beforeDatabasePersist(event);
    Optional<Message> kafkaRedelivery = messageRepository.findByMessageKey(event.getMessageKey());
    if (kafkaRedelivery.isPresent()) {
      return new PersistedMessageResult(
          MessageResponse.from(kafkaRedelivery.get()), false, true);
    }
    Optional<Message> clientRetry = findClientRetry(event);
    if (clientRetry.isPresent()) {
      return new PersistedMessageResult(MessageResponse.from(clientRetry.get()), false, false);
    }

    ChatRoom room =
        chatRoomRepository
            .findById(event.getRoomId())
            .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."));
    User sender =
        userRepository
            .findById(event.getSenderId())
            .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

    if (!chatRoomMemberRepository.existsByChatRoomIdAndUserId(room.getId(), sender.getId())) {
      throw new BusinessException(HttpStatus.FORBIDDEN, "채팅방에 참여하지 않은 사용자입니다.");
    }

    Message message =
        new Message(
            event.getMessageKey(),
            event.getClientMessageId(),
            room,
            sender,
            event.getContent(),
            event.getType());
    message.updateKafkaMetadata(kafkaPartition, kafkaOffset);
    messageRepository.saveAndFlush(message);

    chatRoomMemberRepository.incrementUnreadCountForOtherMembers(room.getId(), sender.getId());
    scheduleRoomCacheEviction(room.getId());

    messagesPersistedCounter.increment();
    messagesLatencyTimer.record(Duration.between(event.getTimestamp(), LocalDateTime.now()));
    return new PersistedMessageResult(MessageResponse.from(message), true, true);
  }

  private Optional<Message> findClientRetry(ChatMessageEvent event) {
    UUID clientMessageId = event.getClientMessageId();
    if (clientMessageId == null) {
      return Optional.empty();
    }
    return messageRepository.findBySenderIdAndClientMessageId(
        event.getSenderId(), clientMessageId);
  }

  private void scheduleRoomCacheEviction(Long roomId) {
    var memberIds = List.copyOf(chatRoomMemberRepository.findUserIdsByRoomId(roomId));
    Runnable eviction = () -> evictRoomCachesBestEffort(roomId, memberIds);
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      eviction.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            eviction.run();
          }
        });
  }

  private void evictRoomCachesBestEffort(Long roomId, Iterable<Long> memberIds) {
    try {
      var roomsCache = cacheManager.getCache("rooms");
      if (roomsCache == null) return;
      for (Long userId : memberIds) {
        try {
          roomsCache.evict(userId);
          roomsCacheEvictionsCounter.increment();
        } catch (RuntimeException exception) {
          log.warn("채팅방 cache eviction 실패: roomId={}, userId={}", roomId, userId, exception);
        }
      }
    } catch (RuntimeException exception) {
      log.warn("채팅방 cache 조회 실패: roomId={}", roomId, exception);
    }
  }
}
