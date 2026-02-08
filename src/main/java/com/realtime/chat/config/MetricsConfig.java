package com.realtime.chat.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicInteger;

// 커스텀 비즈니스 메트릭 등록
@Configuration
public class MetricsConfig {

    @Bean
    public Counter messagesSentCounter(MeterRegistry registry) {
        return Counter.builder("chat.messages.sent")
                .description("메시지 전송 수 (Kafka 발행)")
                .register(registry);
    }

    @Bean
    public Counter messagesPersistedCounter(MeterRegistry registry) {
        return Counter.builder("chat.messages.persisted")
                .description("메시지 DB 저장 수")
                .register(registry);
    }

    @Bean
    public Counter messagesFailedCounter(MeterRegistry registry) {
        return Counter.builder("chat.messages.failed")
                .description("메시지 처리 실패 수")
                .register(registry);
    }

    @Bean
    public AtomicInteger websocketSessionGauge(MeterRegistry registry) {
        AtomicInteger sessions = new AtomicInteger(0);
        registry.gauge("chat.websocket.sessions", sessions);
        return sessions;
    }

    @Bean
    public Timer messagesLatencyTimer(MeterRegistry registry) {
        return Timer.builder("chat.messages.latency")
                .description("Kafka 발행 → DB 저장 지연시간")
                .register(registry);
    }
}
