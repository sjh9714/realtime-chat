package com.realtime.chat.service;

import com.realtime.chat.common.BusinessException;
import com.realtime.chat.domain.ChatRoom;
import com.realtime.chat.domain.RoomType;
import com.realtime.chat.domain.User;
import com.realtime.chat.dto.ChatRoomListResponse;
import com.realtime.chat.dto.ChatRoomResponse;
import com.realtime.chat.dto.CreateDirectRoomRequest;
import com.realtime.chat.dto.CreateGroupRoomRequest;
import com.realtime.chat.repository.ChatRoomMemberRepository;
import com.realtime.chat.repository.ChatRoomRepository;
import com.realtime.chat.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ChatRoomService {

    private final ChatRoomRepository chatRoomRepository;
    private final ChatRoomMemberRepository chatRoomMemberRepository;
    private final UserRepository userRepository;

    // 1:1 채팅방 생성 (이미 존재하면 기존 방 반환)
    @Transactional
    public ChatRoomResponse createDirectRoom(Long userId, CreateDirectRoomRequest request) {
        if (userId.equals(request.getTargetUserId())) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "자기 자신과의 채팅방은 생성할 수 없습니다.");
        }

        User currentUser = findUser(userId);
        User targetUser = findUser(request.getTargetUserId());

        // 기존 1:1 방이 있으면 반환
        return chatRoomRepository.findDirectRoomByUsers(RoomType.DIRECT, userId, request.getTargetUserId())
                .map(ChatRoomResponse::from)
                .orElseGet(() -> {
                    ChatRoom room = new ChatRoom(null, RoomType.DIRECT, currentUser);
                    room.addMember(currentUser);
                    room.addMember(targetUser);
                    chatRoomRepository.save(room);
                    return ChatRoomResponse.from(room);
                });
    }

    // 그룹 채팅방 생성
    @Transactional
    public ChatRoomResponse createGroupRoom(Long userId, CreateGroupRoomRequest request) {
        User currentUser = findUser(userId);

        ChatRoom room = new ChatRoom(request.getName(), RoomType.GROUP, currentUser);
        room.addMember(currentUser);

        // 초기 멤버 추가
        if (request.getMemberIds() != null) {
            for (Long memberId : request.getMemberIds()) {
                if (!memberId.equals(userId)) {
                    User member = findUser(memberId);
                    room.addMember(member);
                }
            }
        }

        chatRoomRepository.save(room);
        return ChatRoomResponse.from(room);
    }

    // 그룹 채팅방 참여
    @Transactional
    public ChatRoomResponse joinRoom(Long userId, Long roomId) {
        User user = findUser(userId);
        ChatRoom room = findRoom(roomId);

        if (room.getType() == RoomType.DIRECT) {
            throw new BusinessException(HttpStatus.BAD_REQUEST, "1:1 채팅방에는 참여할 수 없습니다.");
        }

        if (chatRoomMemberRepository.existsByChatRoomIdAndUserId(roomId, userId)) {
            throw new BusinessException(HttpStatus.CONFLICT, "이미 참여 중인 채팅방입니다.");
        }

        room.addMember(user);
        return ChatRoomResponse.from(room);
    }

    // 내 채팅방 목록 조회
    @Transactional(readOnly = true)
    public List<ChatRoomListResponse> getMyRooms(Long userId) {
        return chatRoomRepository.findAllByUserId(userId).stream()
                .map(room -> ChatRoomListResponse.from(room, userId))
                .toList();
    }

    // 채팅방 상세 조회
    @Transactional(readOnly = true)
    public ChatRoomResponse getRoom(Long userId, Long roomId) {
        ChatRoom room = findRoom(roomId);

        if (!chatRoomMemberRepository.existsByChatRoomIdAndUserId(roomId, userId)) {
            throw new BusinessException(HttpStatus.FORBIDDEN, "채팅방에 참여하지 않은 사용자입니다.");
        }

        return ChatRoomResponse.from(room);
    }

    private User findUser(Long userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다."));
    }

    private ChatRoom findRoom(Long roomId) {
        return chatRoomRepository.findById(roomId)
                .orElseThrow(() -> new BusinessException(HttpStatus.NOT_FOUND, "채팅방을 찾을 수 없습니다."));
    }
}
