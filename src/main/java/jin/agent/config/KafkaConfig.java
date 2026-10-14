package jin.agent.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.*;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.HashMap;
import java.util.Map;

/**
 * 阶段六：Kafka 消息队列显式配置类 (KafkaConfig)
 *
 * <p>架构与设计原则：
 * 1. 显式构建 ProducerFactory 与 KafkaTemplate，拒绝隐式装配黑盒，泛型类型安全严格限定为 <String, String>。
 * 2. 生产端开启 acks=all 强可靠性模式，保证消息写入全部 ISR 副本后再确认。
 * 3. 消费端显式关闭自动提交 (ENABLE_AUTO_COMMIT_CONFIG = false)，
 *    并将监听容器 AckMode 明确设为 MANUAL_IMMEDIATE（手动立即提交），与业务逻辑执行严格绑定。
 */
@Configuration
@EnableKafka
public class KafkaConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConfig.class);

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    // =========================================================================
    // 1. 生产者端核心配置 (ProducerFactory & KafkaTemplate)
    // =========================================================================
    @Bean
    public ProducerFactory<String, String> producerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

        // acks=all 确保所有同步副本集 (ISR) 写入成功
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        // 允许重试 3 次
        props.put(ProducerConfig.RETRIES_CONFIG, 3);

        log.info("【KafkaConfig】初始化 ProducerFactory，连接服务器: {}", bootstrapServers);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> producerFactory) {
        return new KafkaTemplate<>(producerFactory);
    }

    // =========================================================================
    // 2. 消费者端核心配置 (ConsumerFactory & ListenerContainerFactory)
    // =========================================================================
    @Bean
    public ConsumerFactory<String, String> consumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "myagent-consumer-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        // 核心可靠性：关闭自动提交 Offset
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        // 从分区起始位置最早的数据开始拉取
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        log.info("【KafkaConfig】初始化 ConsumerFactory，连接服务器: {}", bootstrapServers);
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> kafkaListenerContainerFactory(
            ConsumerFactory<String, String> consumerFactory) {

        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(consumerFactory);

        // 设置手动立即确认模式：调用 acknowledgment.acknowledge() 时即刻提交位移
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);

        return factory;
    }
}
