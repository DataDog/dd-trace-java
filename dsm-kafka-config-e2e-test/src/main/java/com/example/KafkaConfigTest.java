package com.example;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;

/**
 * Minimal Kafka test application that creates a producer and consumer
 * to exercise the Data Streams Monitoring Kafka config capture feature.
 *
 * When instrumented with the Datadog Java tracer (DD_DATA_STREAMS_ENABLED=true),
 * this should result in kafka_producer and kafka_consumer configs being
 * sent as part of the DSM payload.
 */
public class KafkaConfigTest {

    private static final String TOPIC = "dsm-config-test-topic";

    public static void main(String[] args) throws Exception {
        String bootstrapServers = System.getenv().getOrDefault("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092");

        System.out.println("[KafkaConfigTest] Starting with bootstrap.servers=" + bootstrapServers);
        System.out.println("[KafkaConfigTest] DD_DATA_STREAMS_ENABLED=" + System.getenv("DD_DATA_STREAMS_ENABLED"));

        // Wait for Kafka to be ready
        System.out.println("[KafkaConfigTest] Waiting 10s for Kafka to be ready...");
        Thread.sleep(10000);

        // --- Producer ---
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");
        producerProps.put(ProducerConfig.LINGER_MS_CONFIG, "5");
        producerProps.put(ProducerConfig.CLIENT_ID_CONFIG, "dsm-test-producer");

        System.out.println("[KafkaConfigTest] Creating KafkaProducer...");
        KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps);
        System.out.println("[KafkaConfigTest] KafkaProducer created -- config should have been captured by tracer");

        // Send a few messages
        for (int i = 0; i < 5; i++) {
            String key = "key-" + i;
            String value = "message-" + i;
            producer.send(new ProducerRecord<>(TOPIC, key, value));
            System.out.println("[KafkaConfigTest] Sent: " + key + "=" + value);
        }
        producer.flush();
        System.out.println("[KafkaConfigTest] All messages sent and flushed.");

        // --- Consumer ---
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "dsm-test-consumer-group");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.CLIENT_ID_CONFIG, "dsm-test-consumer");

        System.out.println("[KafkaConfigTest] Creating KafkaConsumer...");
        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps);
        System.out.println("[KafkaConfigTest] KafkaConsumer created -- config should have been captured by tracer");

        consumer.subscribe(Collections.singletonList(TOPIC));

        // Poll for messages
        int totalReceived = 0;
        long startTime = System.currentTimeMillis();
        long timeout = 30000; // 30 seconds
        while (totalReceived < 5 && (System.currentTimeMillis() - startTime) < timeout) {
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(1000));
            for (ConsumerRecord<String, String> record : records) {
                System.out.println("[KafkaConfigTest] Received: " + record.key() + "=" + record.value());
                totalReceived++;
            }
        }
        System.out.println("[KafkaConfigTest] Total messages received: " + totalReceived);

        // Wait for DSM to flush (bucket duration is 10s by default)
        System.out.println("[KafkaConfigTest] Waiting 30s for DSM flush...");
        Thread.sleep(15000);

        // Cleanup
        producer.close();
        consumer.close();

        System.out.println("[KafkaConfigTest] Done. Exiting.");
    }
}
