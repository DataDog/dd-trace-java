import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.datastreams.MockFeaturesDiscovery;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.datastreams.DefaultDataStreamsMonitoring;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.EmbeddedKafkaKraftBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

@WithConfig(key = "data.streams.lite.enabled", value = "true")
@WithConfig(key = "data.streams.lite.origin.cap.per.second", value = "1")
class KafkaDataStreamsLiteOriginCapForkedTest extends AbstractInstrumentationTest {
  private static final String ORDERS = "cap.orders";
  private static final String INVOICES = "cap.invoices";
  private static final String RECEIPTS = "cap.receipts";
  private static final int RECORDS = 20;

  private static final List<QueuedPoint> queued = new CopyOnWriteArrayList<>();

  private EmbeddedKafkaBroker embeddedKafka;

  @BeforeAll
  static void installDataStreamsTracer() {
    DefaultDataStreamsMonitoring dataStreamsMonitoring =
        new DefaultDataStreamsMonitoring(
            new KafkaDataStreamsLiteForkedTest.NoopSink(),
            new MockFeaturesDiscovery(true),
            SystemTimeSource.INSTANCE,
            new KafkaDataStreamsLiteForkedTest.DataStreamsEnabled(),
            (data, serviceNameOverride) -> {},
            TimeUnit.MILLISECONDS.toNanos(50),
            true) {
          @Override
          public boolean add(StatsPoint statsPoint) {
            boolean added = super.add(statsPoint);
            if (added) {
              queued.add(new QueuedPoint(statsPoint));
            }
            return added;
          }
        };
    CoreTracer coreTracer =
        CoreTracer.builder()
            .writer(writer)
            .idGenerationStrategy(IdGenerationStrategy.fromName("SEQUENTIAL"))
            .strictTraceWrites(true)
            .dataStreamsMonitoring(dataStreamsMonitoring)
            .build();
    TracerInstaller.forceInstallGlobalTracer(coreTracer);
    tracer = coreTracer;
  }

  @BeforeEach
  void startBroker() {
    queued.clear();
    embeddedKafka = new EmbeddedKafkaKraftBroker(1, 1, ORDERS, INVOICES, RECEIPTS);
    embeddedKafka.afterPropertiesSet();
  }

  @AfterEach
  void stopBroker() {
    if (embeddedKafka != null) {
      embeddedKafka.destroy();
    }
  }

  @Test
  void overBudgetOriginsStartNoPathwayDownstream() throws Exception {
    Map<String, Object> props = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString());
    try (KafkaProducer<String, String> producer =
        new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
      producer.partitionsFor(ORDERS);
      producer.partitionsFor(INVOICES);
      producer.partitionsFor(RECEIPTS);
      for (int i = 0; i < RECORDS; i++) {
        producer.send(new ProducerRecord<>(ORDERS, "order-" + i)).get();
      }
      // Each message is forwarded while the span of its consume is active
      assertEquals(
          RECORDS, consume("invoicing", ORDERS, value -> send(producer, INVOICES, value)).size());
      assertEquals(
          RECORDS, consume("billing", INVOICES, value -> send(producer, RECEIPTS, value)).size());
    }
    assertEquals(RECORDS, consume("archive", RECEIPTS, value -> {}).size());

    assertFalse(queued.isEmpty());
    List<Long> hashes = new ArrayList<>();
    for (QueuedPoint point : queued) {
      hashes.add(point.hash);
    }
    String origin = "direction:out topic:" + ORDERS;
    for (QueuedPoint point : queued) {
      if (point.parentHash == 0) {
        assertEquals(origin, point.edge, "pathway rooted at " + point + " in " + queued);
      } else {
        assertTrue(hashes.contains(point.parentHash), "orphan " + point + " in " + queued);
      }
    }
    assertTrue(
        queued.stream().anyMatch(point -> point.edge.equals("direction:in topic:" + RECEIPTS)),
        queued.toString());
  }

  private static void send(KafkaProducer<String, String> producer, String topic, String value) {
    try {
      producer.send(new ProducerRecord<>(topic, value)).get();
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  private interface RecordHandler {
    void handle(String value);
  }

  private List<String> consume(String group, String topic, RecordHandler handler) {
    Map<String, Object> props =
        KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), group, "false");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    List<String> values = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
      consumer.subscribe(Collections.singletonList(topic));
      long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20);
      while (values.size() < RECORDS && System.currentTimeMillis() < deadline) {
        for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
          handler.handle(record.value());
          values.add(record.value());
        }
      }
    }
    return values;
  }

  private static final class QueuedPoint {
    final String edge;
    final long hash;
    final long parentHash;

    QueuedPoint(StatsPoint point) {
      this.edge = point.getTags().getDirection() + " " + point.getTags().getTopic();
      this.hash = point.getHash();
      this.parentHash = point.getParentHash();
    }

    @Override
    public String toString() {
      return edge + " hash=" + hash + " parent=" + parentHash;
    }
  }
}
