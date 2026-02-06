package com.realtime.chat.controller;

import com.realtime.chat.dto.MessagePageResponse;
import com.realtime.chat.dto.ReadReceiptRequest;
import com.realtime.chat.service.MessageService;
import com.realtime.chat.service.ReadReceiptService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms/{roomId}")
@RequiredArgsConstructor
public class MessageController {

    private final MessageService messageService;
    private final ReadReceiptService readReceiptService;

    // 커서 기반 페이지네이션으로 메시지 이력 조회
    @GetMapping("/messages")
    public ResponseEntity<MessagePageResponse> getMessages(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long roomId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(messageService.getMessages(userId, roomId, cursor, size));
    }

    // 읽음 처리
    @PostMapping("/read")
    public ResponseEntity<Void> markAsRead(
            @AuthenticationPrincipal Long userId,
            @PathVariable Long roomId,
            @Valid @RequestBody ReadReceiptRequest request) {
        readReceiptService.markAsRead(userId, roomId, request.getLastReadMessageId());
        return ResponseEntity.ok().build();
    }
}
