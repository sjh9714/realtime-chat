package com.realtime.chat.repository;

import com.realtime.chat.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // 멱등성 체크: 동일 messageKey 존재 여부
    boolean existsByMessageKey(UUID messageKey);

    // 커서 기반 페이지네이션: cursor보다 작은 id의 메시지를 size+1개 조회 (hasMore 판단)
    @Query("""
            SELECT m FROM Message m
            JOIN FETCH m.sender
            WHERE m.chatRoom.id = :roomId AND m.id < :cursor
            ORDER BY m.id DESC
            LIMIT :limit
            """)
    List<Message> findByRoomIdWithCursor(
            @Param("roomId") Long roomId,
            @Param("cursor") Long cursor,
            @Param("limit") int limit);

    // 첫 페이지 조회 (cursor 없을 때)
    @Query("""
            SELECT m FROM Message m
            JOIN FETCH m.sender
            WHERE m.chatRoom.id = :roomId
            ORDER BY m.id DESC
            LIMIT :limit
            """)
    List<Message> findByRoomIdLatest(
            @Param("roomId") Long roomId,
            @Param("limit") int limit);

    // 읽음 처리용: 특정 메시지 이후 안읽은 메시지 수 계산
    @Query("""
            SELECT COUNT(m) FROM Message m
            WHERE m.chatRoom.id = :roomId AND m.id > :lastReadMessageId
            """)
    int countUnreadMessages(
            @Param("roomId") Long roomId,
            @Param("lastReadMessageId") Long lastReadMessageId);
}
