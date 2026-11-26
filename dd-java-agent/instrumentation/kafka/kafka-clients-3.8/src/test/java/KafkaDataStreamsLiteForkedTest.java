import static datadog.trace.api.datastreams.PathwayContext.PROPAGATION_KEY_BASE64;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.context.ContextScope;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.datastreams.MockFeaturesDiscovery;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.DDTags;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.api.sampling.PrioritySampling;
import datadog.trace.api.sampling.SamplingMechanism;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.Sink;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.datastreams.DefaultDataStreamsMonitoring;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
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
class KafkaDataStreamsLiteForkedTest extends AbstractInstrumentationTest {
  private static final String TOPIC = "lite.topic";
  private static final String GROUP = "lite-group";
  private static final int RECORDS = 100;

  // Edge tags of each queued point; bootstrap types such as StatsPoint can't appear in fields
  private static final List<String> queuedEdges = new CopyOnWriteArrayList<>();
  private static final List<String> queuedDetails = new CopyOnWriteArrayList<>();

  private EmbeddedKafkaBroker embeddedKafka;

  @BeforeAll
  static void installDataStreamsTracer() {
    DefaultDataStreamsMonitoring dataStreamsMonitoring =
        new DefaultDataStreamsMonitoring(
            new NoopSink(),
            new MockFeaturesDiscovery(true),
            SystemTimeSource.INSTANCE,
            new DataStreamsEnabled(),
            (data, serviceNameOverride) -> {},
            TimeUnit.MILLISECONDS.toNanos(50),
            true) {
          @Override
          public boolean add(StatsPoint statsPoint) {
            boolean queued = super.add(statsPoint);
            if (queued) {
              queuedEdges.add(
                  statsPoint.getTags().getTopic()
                      + " "
                      + statsPoint.getTags().getDirection()
                      + " "
                      + statsPoint.getTags().getGroup());
              queuedDetails.add(
                  statsPoint.getTags()
                      + " hash="
                      + statsPoint.getHash()
                      + " parent="
                      + statsPoint.getParentHash());
            }
            return queued;
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
    queuedEdges.clear();
    queuedDetails.clear();
    embeddedKafka = new EmbeddedKafkaKraftBroker(1, 1, TOPIC);
    embeddedKafka.afterPropertiesSet();
  }

  @AfterEach
  void stopBroker() {
    if (embeddedKafka != null) {
      embeddedKafka.destroy();
    }
  }

  @Test
  void oneSpanProducingManyRecordsToOneTopicQueuesOnePoint() throws Exception {
    List<ProducerRecord<String, String>> records = produceUnderOneSpan();

    for (ProducerRecord<String, String> record : records) {
      assertNotNull(record.headers().lastHeader(PROPAGATION_KEY_BASE64));
    }
    assertEquals(1, queued("topic:" + TOPIC + " direction:out null"));
  }

  @Test
  void consumingManyRecordsFromOneTopicQueuesOnePointPerGroup() throws Exception {
    produceUnderOneSpan();

    assertEquals(RECORDS, consumeAll());
    assertEquals(
        1, queued("topic:" + TOPIC + " direction:in group:" + GROUP), queuedDetails.toString());
  }

  @Test
  void liteCreatesNoPollSpansAndNoDsmEnabledTag() throws Exception {
    produceUnderOneSpan();
    assertEquals(RECORDS, consumeAll());
    writer.waitForTracesMax(RECORDS + 1, 10);

    List<DDSpan> spans = allSpans();
    assertTrue(spans.stream().anyMatch(span -> "kafka.consume".contentEquals(name(span))));
    for (DDSpan span : spans) {
      assertFalse("kafka.poll".contentEquals(name(span)), spans.toString());
      assertNull(span.getTag(DDTags.DSM_ENABLED), span.toString());
    }
  }

  @Test
  void pathwayHashIsOnlyOnSpansOfKeptTracesAndMatchesTheHeader() throws Exception {
    List<ProducerRecord<String, String>> kept = produceUnderOneSpan(null);
    writer.waitForTracesMax(1, 10);
    List<DDSpan> keptSpans = produceSpans();
    writer.clear();
    List<ProducerRecord<String, String>> dropped =
        produceUnderOneSpan((int) PrioritySampling.USER_DROP);
    writer.waitForTracesMax(1, 10);
    List<DDSpan> droppedSpans = produceSpans();

    assertEquals(RECORDS, keptSpans.size());
    for (DDSpan span : keptSpans) {
      assertEquals(headerHash(kept.get(0)), span.getTag(DDTags.PATHWAY_HASH), span.toString());
    }
    assertEquals(RECORDS, droppedSpans.size());
    for (DDSpan span : droppedSpans) {
      assertNull(span.getTag(DDTags.PATHWAY_HASH), span.toString());
    }
    // Sampling changes the span tag only: the header is still propagated, with the same pathway
    for (ProducerRecord<String, String> record : dropped) {
      assertEquals(headerHash(kept.get(0)), headerHash(record));
    }
  }

  /** The pathway hash in a record's DSM header, decoded with the JDK base64 decoder. */
  private static String headerHash(ProducerRecord<String, String> record) {
    Header header = record.headers().lastHeader(PROPAGATION_KEY_BASE64);
    assertNotNull(header);
    byte[] raw = Base64.getDecoder().decode(header.value());
    return Long.toUnsignedString(ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getLong());
  }

  private static CharSequence name(DDSpan span) {
    return span.getOperationName();
  }

  private static List<DDSpan> allSpans() {
    List<DDSpan> spans = new ArrayList<>();
    for (List<DDSpan> trace : writer) {
      spans.addAll(trace);
    }
    return spans;
  }

  private static List<DDSpan> produceSpans() {
    List<DDSpan> spans = new ArrayList<>();
    for (DDSpan span : allSpans()) {
      if ("kafka.produce".contentEquals(name(span))) {
        spans.add(span);
      }
    }
    return spans;
  }

  private int consumeAll() {
    Map<String, Object> props =
        KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), GROUP, "false");
    props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    int consumed = 0;
    try (KafkaConsumer<String, String> consumer =
        new KafkaConsumer<>(props, new StringDeserializer(), new StringDeserializer())) {
      consumer.subscribe(Collections.singletonList(TOPIC));
      long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(20);
      while (consumed < RECORDS && System.currentTimeMillis() < deadline) {
        for (ConsumerRecord<String, String> ignored : consumer.poll(Duration.ofMillis(200))) {
          consumed++;
        }
      }
    }
    return consumed;
  }

  private List<ProducerRecord<String, String>> produceUnderOneSpan() throws Exception {
    return produceUnderOneSpan(null);
  }

  private List<ProducerRecord<String, String>> produceUnderOneSpan(Integer samplingPriority)
      throws Exception {
    List<ProducerRecord<String, String>> records = new ArrayList<>();
    Map<String, Object> props = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString());
    try (KafkaProducer<String, String> producer =
        new KafkaProducer<>(props, new StringSerializer(), new StringSerializer())) {
      // Records sent before the cluster id is known carry different tags, so they form a separate
      // pathway
      producer.partitionsFor(TOPIC);
      AgentSpan parent = startSpan("test", "parent");
      if (samplingPriority != null) {
        parent.setSamplingPriority(samplingPriority, SamplingMechanism.MANUAL);
      }
      try (ContextScope ignored = activateSpan(parent)) {
        for (int i = 0; i < RECORDS; i++) {
          ProducerRecord<String, String> record = new ProducerRecord<>(TOPIC, "message-" + i);
          records.add(record);
          producer.send(record).get();
        }
      } finally {
        parent.finish();
      }
    }
    return records;
  }

  private static long queued(String edge) {
    return queuedEdges.stream().filter(edge::equals).count();
  }

  static final class DataStreamsEnabled implements Supplier<TraceConfig> {
    private final TraceConfig traceConfig = mock(TraceConfig.class);

    DataStreamsEnabled() {
      when(traceConfig.isDataStreamsEnabled()).thenReturn(true);
    }

    @Override
    public TraceConfig get() {
      return traceConfig;
    }
  }

  static final class NoopSink implements Sink {
    @Override
    public void accept(int messageCount, ByteBuffer buffer) {}

    @Override
    public void register(EventListener listener) {}
  }
}
