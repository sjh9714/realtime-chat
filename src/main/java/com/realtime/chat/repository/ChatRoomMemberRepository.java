package com.realtime.chat.repository;

import com.realtime.chat.domain.ChatRoomMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {

    Optional<ChatRoomMember> findByChatRoomIdAndUserId(Long chatRoomId, Long userId);

    List<ChatRoomMember> findAllByChatRoomId(Long chatRoomId);

    boolean existsByChatRoomIdAndUserId(Long chatRoomId, Long userId);

    // 발신자를 제외한 같은 방 멤버들의 unreadCount 증가
    @Modifying
    @Query("""
            UPDATE ChatRoomMember m
            SET m.unreadCount = m.unreadCount + 1
            WHERE m.chatRoom.id = :roomId AND m.user.id <> :senderId
            """)
    int incrementUnreadCountForOtherMembers(
            @Param("roomId") Long roomId,
            @Param("senderId") Long senderId);
}
