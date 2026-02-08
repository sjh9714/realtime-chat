package com.realtime.chat.repository;

import com.realtime.chat.domain.ChatRoom;
import com.realtime.chat.domain.RoomType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.realtime.chat.dto.ChatRoomListResponse;

import java.util.List;
import java.util.Optional;

public interface ChatRoomRepository extends JpaRepository<ChatRoom, Long> {

    // 1:1 채팅방 중복 방지: 두 유저가 이미 DIRECT 방에 함께 있는지 확인
    @Query("""
            SELECT cr FROM ChatRoom cr
            WHERE cr.type = :type
            AND cr.id IN (
                SELECT m1.chatRoom.id FROM ChatRoomMember m1
                WHERE m1.user.id = :userId1
            )
            AND cr.id IN (
                SELECT m2.chatRoom.id FROM ChatRoomMember m2
                WHERE m2.user.id = :userId2
            )
            """)
    Optional<ChatRoom> findDirectRoomByUsers(
            @Param("type") RoomType type,
            @Param("userId1") Long userId1,
            @Param("userId2") Long userId2);

    // 내 채팅방 목록 조회 (N+1 해결: JPQL 프로젝션으로 단일 쿼리)
    @Query("""
            SELECT new com.realtime.chat.dto.ChatRoomListResponse(
                cr.id, cr.name, cr.type,
                (SELECT COUNT(m2) FROM ChatRoomMember m2 WHERE m2.chatRoom.id = cr.id),
                m.unreadCount, cr.createdAt
            )
            FROM ChatRoomMember m
            JOIN m.chatRoom cr
            WHERE m.user.id = :userId
            """)
    List<ChatRoomListResponse> findAllWithMemberInfoByUserId(@Param("userId") Long userId);
}
