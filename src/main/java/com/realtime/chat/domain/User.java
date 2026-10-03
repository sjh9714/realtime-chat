package com.realtime.chat.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class User {

  @Id
  @GeneratedValue(strategy = GenerationType.IDENTITY)
  private Long id;

  @Column(nullable = false, unique = true)
  private String email;

  @Column(nullable = false)
  private String password;

  @Column(nullable = false, length = 50)
  private String nickname;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false, length = 20)
  private UserStatus status = UserStatus.OFFLINE;

  private LocalDateTime lastSeenAt;

  /**
   * 사람이 아니라 봇인가.
   *
   * <p>화면이 이름 옆에 BOT 배지를 그리는 근거다. 봇을 사람인 척 두면 읽는 사람이
   * 오해하므로 표시한다. Slack·Discord·카카오워크가 모두 그렇게 한다.
   */
  @Column(nullable = false)
  private boolean bot = false;

  @Column(nullable = false, updatable = false)
  private LocalDateTime createdAt;

  public User(String email, String password, String nickname) {
    this(email, password, nickname, false);
  }

  public User(String email, String password, String nickname, boolean bot) {
    this.email = email;
    this.password = password;
    this.nickname = nickname;
    this.status = UserStatus.OFFLINE;
    this.bot = bot;
  }

  @PrePersist
  protected void onCreate() {
    this.createdAt = LocalDateTime.now();
  }

  public void updateStatus(UserStatus status) {
    this.status = status;
    if (status == UserStatus.OFFLINE) {
      this.lastSeenAt = LocalDateTime.now();
    }
  }
}
