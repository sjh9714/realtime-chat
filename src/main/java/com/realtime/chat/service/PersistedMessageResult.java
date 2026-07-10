package com.realtime.chat.service;

import com.realtime.chat.dto.MessageResponse;

public record PersistedMessageResult(
    MessageResponse message, boolean newlyCreated, boolean shouldBroadcast) {}
