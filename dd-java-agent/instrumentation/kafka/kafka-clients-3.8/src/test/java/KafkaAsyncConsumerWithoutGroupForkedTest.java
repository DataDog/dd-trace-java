import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.core.DDSpan;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/**
 * An AsyncKafkaConsumer (group.protocol=consumer) without a group.id and with Data Streams off goes
 * through ConstructorAdvice, not LegacyConstructorAdvice, so this checks that its spans still get
 * the bootstrap servers and the cluster ID.
 */
@WithConfig(key = "data.streams.enabled", value = "false")
class KafkaAsyncConsumerWithoutGroupForkedTest extends AbstractInstrumentationTest {
  private static final String TOPIC = "async-consumer-without-group";
  private static final TopicPartition PARTITION = new TopicPartition(TOPIC, 0);
  private static final Duration TIMEOUT = Duration.ofSeconds(15);

  private static EmbeddedKafkaKraftBroker broker;

  @BeforeAll
  static void startBroker() {
    broker = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);
    broker.brokerProperty("group.coordinator.rebalance.protocols", "classic,consumer");
    broker.brokerProperty("group.coordinator.new.enable", "true");
    broker.afterPropertiesSet();
  }

  @AfterAll
  static void stopBroker() {
    if (broker != null) {
      broker.destroy();
    }
  }

  @Test
  void asyncConsumerWithoutAGroupTagsBootstrapServersAndTheClusterId() throws Exception {
    Map<String, Object> properties = KafkaTestUtils.consumerProps("unused", "false", broker);
    properties.remove(ConsumerConfig.GROUP_ID_CONFIG);
    properties.remove(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG);
    properties.remove(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG);
    properties.put(ConsumerConfig.GROUP_PROTOCOL_CONFIG, "consumer");
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

    List<String> received = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(properties);
        KafkaProducer<String, String> producer =
            new KafkaProducer<>(
                KafkaTestUtils.producerProps(broker.getBrokersAsString()),
                new StringSerializer(),
                new StringSerializer())) {
      assertEquals("AsyncKafkaConsumer", delegateClassName(consumer));
      consumer.assign(Collections.singletonList(PARTITION));

      producer.send(new ProducerRecord<>(TOPIC, 0, null, "Hello without a group!")).get();
      long deadline = System.nanoTime() + TIMEOUT.toNanos();
      while (received.isEmpty() && System.nanoTime() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(100))) {
          received.add(record.value());
        }
      }
    }

    assertEquals(Collections.singletonList("Hello without a group!"), received);
    DDSpan consumeSpan = awaitSpan(Tags.SPAN_KIND_CONSUMER);
    DDSpan produceSpan = awaitSpan(Tags.SPAN_KIND_PRODUCER);
    assertEquals(
        broker.getBrokersAsString(),
        consumeSpan.getTag(InstrumentationTags.KAFKA_BOOTSTRAP_SERVERS));
    Object clusterId = consumeSpan.getTag(InstrumentationTags.KAFKA_CLUSTER_ID);
    assertInstanceOf(String.class, clusterId);
    assertFalse(((String) clusterId).isEmpty());
    assertNull(consumeSpan.getTag(InstrumentationTags.CONSUMER_GROUP));
    assertEquals(clusterId, produceSpan.getTag(InstrumentationTags.KAFKA_CLUSTER_ID));
  }

  private static String delegateClassName(KafkaConsumer<?, ?> consumer) throws Exception {
    Field delegate = KafkaConsumer.class.getDeclaredField("delegate");
    delegate.setAccessible(true);
    return delegate.get(consumer).getClass().getSimpleName();
  }

  private DDSpan awaitSpan(String kind) {
    blockUntilTracesMatch(traces -> findSpan(traces, kind) != null);
    return findSpan(writer, kind);
  }

  private static DDSpan findSpan(List<List<DDSpan>> traces, String kind) {
    for (List<DDSpan> trace : traces) {
      for (DDSpan span : trace) {
        if (kind.equals(span.getTag(Tags.SPAN_KIND))) {
          return span;
        }
      }
    }
    return null;
  }
}
