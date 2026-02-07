package com.realtime.chat.config;

import com.realtime.chat.service.RedisPubSubService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
public class RedisConfig {

    public static final String CHAT_ROOM_CHANNEL_PREFIX = "chat:room:";
    public static final String CHAT_ROOM_PATTERN = "chat:room:*";
    public static final String PRESENCE_CHANNEL = "chat:presence";

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    // Redis Pub/Sub 리스너 컨테이너: chat:room:* + chat:presence 채널 구독
    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter messageListenerAdapter,
            MessageListenerAdapter presenceListenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(messageListenerAdapter, new PatternTopic(CHAT_ROOM_PATTERN));
        container.addMessageListener(presenceListenerAdapter, new ChannelTopic(PRESENCE_CHANNEL));
        return container;
    }

    @Bean
    public MessageListenerAdapter messageListenerAdapter(RedisPubSubService redisPubSubService) {
        return new MessageListenerAdapter(redisPubSubService, "onMessage");
    }

    @Bean
    public MessageListenerAdapter presenceListenerAdapter(RedisPubSubService redisPubSubService) {
        return new MessageListenerAdapter(redisPubSubService, "onPresenceMessage");
    }
}
