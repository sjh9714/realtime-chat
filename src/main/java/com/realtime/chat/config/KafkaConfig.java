package com.realtime.chat.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

@Configuration
public class KafkaConfig {

    public static final String MESSAGES_TOPIC = "chat.messages";
    public static final String READ_RECEIPTS_TOPIC = "chat.read-receipts";
    public static final String MESSAGES_DLT = "chat.messages.dlt";

    @Value("${spring.kafka.bootstrap-servers}")
    private String bootstrapServers;

    // 토픽 생성
    @Bean
    public NewTopic messagesTopic() {
        return TopicBuilder.name(MESSAGES_TOPIC)
                .partitions(6)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic readReceiptsTopic() {
        return TopicBuilder.name(READ_RECEIPTS_TOPIC)
                .partitions(3)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic messagesDltTopic() {
        return TopicBuilder.name(MESSAGES_DLT)
                .partitions(1)
                .replicas(1)
                .build();
    }

    // Consumer 공통 설정: manual commit
    private Map<String, Object> consumerConfigs(String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.ISOLATION_LEVEL_CONFIG, "read_committed");
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.realtime.chat.event");
        return props;
    }

    // DB 저장용 Consumer (Group 1)
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> persistenceListenerFactory() {
        return createListenerFactory("chat-persistence");
    }

    // 브로드캐스트용 Consumer (Group 2)
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> broadcastListenerFactory() {
        return createListenerFactory("chat-broadcast");
    }

    // 읽음 처리용 Consumer
    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, Object> readReceiptListenerFactory() {
        return createListenerFactory("chat-read-receipt");
    }

    private ConcurrentKafkaListenerContainerFactory<String, Object> createListenerFactory(String groupId) {
        ConsumerFactory<String, Object> consumerFactory =
                new DefaultKafkaConsumerFactory<>(consumerConfigs(groupId));

        ConcurrentKafkaListenerContainerFactory<String, Object> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL);

        // 3회 재시도 후 DLT로 격리
        DefaultErrorHandler errorHandler = new DefaultErrorHandler(new FixedBackOff(1000L, 3));
        factory.setCommonErrorHandler(errorHandler);

        return factory;
    }
}
