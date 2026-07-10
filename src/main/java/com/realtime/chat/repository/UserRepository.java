package com.realtime.chat.repository;

import com.realtime.chat.domain.User;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<User, Long> {

  Optional<User> findByEmail(String email);

  boolean existsByEmail(String email);

  @Query(
      """
      SELECT u FROM User u
      WHERE u.id <> :currentUserId
      AND LOWER(u.nickname) LIKE LOWER(CONCAT('%', :nickname, '%'))
      ORDER BY u.nickname ASC, u.id ASC
      LIMIT :limit
      """)
  List<User> searchByNickname(
      @Param("currentUserId") Long currentUserId,
      @Param("nickname") String nickname,
      @Param("limit") int limit);
}
