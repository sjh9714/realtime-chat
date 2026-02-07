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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
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

    @KafkaListener(
            topics = KafkaConfig.MESSAGES_TOPIC,
            containerFactory = "persistenceListenerFactory"
    )
    @Transactional
    public void consume(ConsumerRecord<String, ChatMessageEvent> record, Acknowledgment ack) {
        ChatMessageEvent event = record.value();
        log.debug("메시지 수신 (persistence): messageKey={}, roomId={}", event.getMessageKey(), event.getRoomId());

        try {
            // 멱등성 체크: 동일 messageKey가 이미 저장되어 있으면 스킵
            if (messageRepository.existsByMessageKey(event.getMessageKey())) {
                log.info("중복 메시지 스킵: messageKey={}", event.getMessageKey());
                ack.acknowledge();
                return;
            }

            ChatRoom room = chatRoomRepository.findById(event.getRoomId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."));
            User sender = userRepository.findById(event.getSenderId())
                    .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));

            Message message = new Message(
                    event.getMessageKey(),
                    room,
                    sender,
                    event.getContent(),
                    event.getType()
            );
            message.updateKafkaMetadata(record.partition(), record.offset());
            messageRepository.save(message);

            // 발신자를 제외한 멤버들의 unreadCount 증가
            chatRoomMemberRepository.incrementUnreadCountForOtherMembers(event.getRoomId(), event.getSenderId());

            ack.acknowledge();
            log.debug("메시지 저장 완료: messageKey={}, id={}", event.getMessageKey(), message.getId());

        } catch (Exception e) {
            log.error("메시지 저장 실패: messageKey={}, topic={}, partition={}, offset={}",
                    event.getMessageKey(), record.topic(), record.partition(), record.offset(), e);
            throw e; // ErrorHandler가 재시도 후 DLT로 보냄
        }
    }
}
