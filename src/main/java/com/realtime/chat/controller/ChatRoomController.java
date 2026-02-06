package com.realtime.chat.controller;

import com.realtime.chat.dto.ChatRoomListResponse;
import com.realtime.chat.dto.ChatRoomResponse;
import com.realtime.chat.dto.CreateDirectRoomRequest;
import com.realtime.chat.dto.CreateGroupRoomRequest;
import com.realtime.chat.service.ChatRoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class ChatRoomController {

    private final ChatRoomService chatRoomService;

    @PostMapping("/direct")
    public ResponseEntity<ChatRoomResponse> createDirectRoom(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateDirectRoomRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(chatRoomService.createDirectRoom(userId, request));
    }

    @PostMapping("/group")
    public ResponseEntity<ChatRoomResponse> createGroupRoom(
            @AuthenticationPrincipal Long userId,
            @Valid @RequestBody CreateGroupRoomRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(chatRoomService.createGroupRoom(userId, request));
    }

    @PostMapping("/{roomId}/join")
    public ResponseEntity<ChatRoomResponse> joinRoom(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long roomId) {
        return ResponseEntity.ok(chatRoomService.joinRoom(userId, roomId));
    }

    @GetMapping
    public ResponseEntity<List<ChatRoomListResponse>> getMyRooms(
            @AuthenticationPrincipal Long userId) {
        return ResponseEntity.ok(chatRoomService.getMyRooms(userId));
    }

    @GetMapping("/{roomId}")
    public ResponseEntity<ChatRoomResponse> getRoom(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long roomId) {
        return ResponseEntity.ok(chatRoomService.getRoom(userId, roomId));
    }
}
