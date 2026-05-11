package com.realtime.chat.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "messages",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_messages_sender_client_message",
            columnNames = {"sender_id", "client_message_id"}))
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Message {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private UUID messageKey;

  @Column(name = "client_message_id", nullable = false)
  private UUID clientMessageId;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "room_id", nullable = false)
  private ChatRoom chatRoom;

  @ManyToOne(fetch = FetchType.LAZY)
  @JoinColumn(name = "sender_id", nullable = false)
  private User sender;

  @Column(nullable = false, columnDefinition = "TEXT")
  private String content;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private MessageType type;

  private Integer kafkaPartition;

  private Long kafkaOffset;

  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;

  public Message(
      UUID messageKey, ChatRoom chatRoom, User sender, String content, MessageType type) {
    this(messageKey, UUID.randomUUID(), chatRoom, sender, content, type);
  }

  public Message(
      UUID messageKey,
      UUID clientMessageId,
      ChatRoom chatRoom,
      User sender,
      String content,
      MessageType type) {
    this.messageKey = messageKey;
    this.clientMessageId = clientMessageId != null ? clientMessageId : UUID.randomUUID();
    this.chatRoom = chatRoom;
    this.sender = sender;
    this.content = content;
    this.type = type;
  }

  @PrePersist
  protected void onCreate() {
    this.createdAt = LocalDateTime.now();
  }

  public void updateKafkaMetadata(int partition, long offset) {
    this.kafkaPartition = partition;
    this.kafkaOffset = offset;
  }
}
