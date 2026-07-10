package com.realtime.chat.repository;

import com.realtime.chat.domain.ChatRoomMember;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ChatRoomMemberRepository extends JpaRepository<ChatRoomMember, Long> {

  Optional<ChatRoomMember> findByChatRoomIdAndUserId(Long chatRoomId, Long userId);

  List<ChatRoomMember> findAllByChatRoomId(Long chatRoomId);

  @Query(
      """
      SELECT m.user.id FROM ChatRoomMember m
      WHERE m.chatRoom.id = :roomId
      """)
  List<Long> findUserIdsByRoomId(@Param("roomId") Long roomId);

  @Query(
      """
      SELECT m.chatRoom.id, m.user.nickname FROM ChatRoomMember m
      WHERE m.chatRoom.id IN :roomIds AND m.user.id <> :userId
      ORDER BY m.chatRoom.id ASC, m.id ASC
      """)
  List<Object[]> findOtherMemberNicknames(
      @Param("roomIds") Collection<Long> roomIds, @Param("userId") Long userId);

  @Query(
      """
      SELECT m.chatRoom.id FROM ChatRoomMember m
      WHERE m.user.id = :userId
      ORDER BY m.chatRoom.id ASC
      """)
  List<Long> findRoomIdsByUserId(@Param("userId") Long userId);

  boolean existsByChatRoomIdAndUserId(Long chatRoomId, Long userId);

  // 발신자를 제외한 같은 방 멤버들의 unreadCount 증가
  @Modifying
  @Query(
      """
      UPDATE ChatRoomMember m
      SET m.unreadCount = m.unreadCount + 1
      WHERE m.chatRoom.id = :roomId AND m.user.id <> :senderId
      """)
  int incrementUnreadCountForOtherMembers(
      @Param("roomId") Long roomId, @Param("senderId") Long senderId);
}
