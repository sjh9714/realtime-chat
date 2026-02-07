package com.realtime.chat.controller;

import com.realtime.chat.service.PresenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class PresenceController {

    private final PresenceService presenceService;

    // 채팅방 멤버 중 온라인인 유저 ID 목록
    @GetMapping("/{roomId}/members/online")
    public ResponseEntity<Set<Long>> getOnlineMembers(@PathVariable Long roomId) {
        return ResponseEntity.ok(presenceService.getOnlineMembers(roomId));
    }
}
