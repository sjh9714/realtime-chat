package com.realtime.chat.config;

import com.realtime.chat.repository.ChatRoomMemberRepository;
import java.security.Principal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class WebSocketAuthorizationInterceptor implements ChannelInterceptor {

  private static final Pattern ROOM_TOPIC_PATTERN = Pattern.compile("^/topic/room\\.(\\d+)$");
  private static final String ROOM_TOPIC_PREFIX = "/topic/room.";

  private final ChatRoomMemberRepository chatRoomMemberRepository;

  @Override
  public Message<?> preSend(Message<?> message, MessageChannel channel) {
    StompHeaderAccessor accessor =
        MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
    if (accessor == null || !StompCommand.SUBSCRIBE.equals(accessor.getCommand())) {
      return message;
    }

    String destination = accessor.getDestination();
    Long roomId = extractRoomId(destination);
    if (roomId == null) {
      if (isRoomTopicCandidate(destination)) {
        throw new AccessDeniedException("잘못된 채팅방 구독 경로입니다.");
      }
      return message;
    }

    Principal principal = accessor.getUser();
    if (principal == null) {
      throw new AccessDeniedException("인증된 사용자만 채팅방을 구독할 수 있습니다.");
    }

    Long userId = parseUserId(principal);
    if (!chatRoomMemberRepository.existsByChatRoomIdAndUserId(roomId, userId)) {
      log.warn("채팅방 구독 권한 없음: userId={}, roomId={}", userId, roomId);
      throw new AccessDeniedException("채팅방을 구독할 권한이 없습니다.");
    }

    return message;
  }

  private Long extractRoomId(String destination) {
    if (destination == null) {
      return null;
    }
    Matcher matcher = ROOM_TOPIC_PATTERN.matcher(destination);
    if (!matcher.matches()) {
      return null;
    }
    try {
      return Long.parseLong(matcher.group(1));
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private boolean isRoomTopicCandidate(String destination) {
    return destination != null
        && (destination.equals("/topic/room") || destination.startsWith(ROOM_TOPIC_PREFIX));
  }

  private Long parseUserId(Principal principal) {
    try {
      return Long.parseLong(principal.getName());
    } catch (NumberFormatException e) {
      throw new AccessDeniedException("인증 정보를 확인할 수 없습니다.", e);
    }
  }
}
