package com.realtime.chat.bot;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄링을 안내봇 때문에만 켠다.
 *
 * <p>{@code ChatApplication}에 {@code @EnableScheduling}을 달면 운영에서도 켜진다.
 * 지금 정기 작업이 필요한 것은 데모의 안내봇뿐이라 여기서만 켠다.
 */
@Configuration
@Profile("demo & !prod")
@EnableScheduling
public class ChatBotSchedulingConfig {}
