import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.datastreams.MockFeaturesDiscovery;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.Pair;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.DataStreamsTransactionExtractor;
import datadog.trace.api.sampling.SamplingRule.SpanSamplingRule;
import datadog.trace.api.sampling.SamplingRule.TraceSamplingRule;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.Sink;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.datastreams.DefaultDataStreamsMonitoring;
import datadog.trace.core.datastreams.StatsBucket;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.utils.KafkaTestUtils;

@WithConfig(key = "data.streams.enabled", value = "true")
class KafkaProducerFailureDataStreamsForkedTest extends AbstractInstrumentationTest {
  private static final String OK_TOPIC = "ok.topic";
  private static final String REJECTING_TOPIC = "rejecting.topic";

  private static final List<StatsBucket> dataStreamsPayloads = new CopyOnWriteArrayList<>();

  private EmbeddedKafkaBroker embeddedKafka;

  // AbstractInstrumentationTest has no data streams support, so swap in a tracer whose data
  // streams monitoring records the payloads it flushes.
  @BeforeAll
  static void installDataStreamsTracer() {
    DefaultDataStreamsMonitoring dataStreamsMonitoring =
        new DefaultDataStreamsMonitoring(
            new NoopSink(),
            new MockFeaturesDiscovery(true),
            SystemTimeSource.INSTANCE,
            new DataStreamsEnabledTraceConfig(),
            (data, serviceNameOverride) -> dataStreamsPayloads.addAll(data),
            TimeUnit.MILLISECONDS.toNanos(50));
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
    dataStreamsPayloads.clear();
    embeddedKafka = new EmbeddedKafkaBroker(1, true, OK_TOPIC);
    embeddedKafka.afterPropertiesSet();
    embeddedKafka.addTopics(
        new NewTopic(REJECTING_TOPIC, 1, (short) 1)
            .configs(Collections.singletonMap("max.message.bytes", "512")));
  }

  @AfterEach
  void stopBroker() {
    if (embeddedKafka != null) {
      embeddedKafka.destroy();
    }
  }

  @Test
  void produceRejectedByTheBrokerDoesNotReportAKafkaProduceBacklog() throws Exception {
    try (KafkaProducer<String, String> producer = newProducer(Collections.emptyMap())) {
      assertThrows(
          ExecutionException.class,
          () -> producer.send(new ProducerRecord<>(REJECTING_TOPIC, repeat('x', 2048))).get());

      RecordMetadata ok = producer.send(new ProducerRecord<>(OK_TOPIC, "hello")).get();
      waitForProduceBacklog(OK_TOPIC);

      assertEquals(
          Collections.singletonList(
              "topic:" + OK_TOPIC + " partition:" + ok.partition() + " offset:" + ok.offset()),
          produceBacklogs());
    }
  }

  @Test
  void produceRejectedByTheClientDoesNotReportAKafkaProduceBacklog() throws Exception {
    try (KafkaProducer<String, String> failingProducer =
            newProducer(Collections.singletonMap(ProducerConfig.MAX_REQUEST_SIZE_CONFIG, 10));
        KafkaProducer<String, String> producer = newProducer(Collections.emptyMap())) {
      assertThrows(
          ExecutionException.class,
          () -> failingProducer.send(new ProducerRecord<>(REJECTING_TOPIC, "hello")).get());

      RecordMetadata ok = producer.send(new ProducerRecord<>(OK_TOPIC, "hello")).get();
      waitForProduceBacklog(OK_TOPIC);

      assertEquals(
          Collections.singletonList(
              "topic:" + OK_TOPIC + " partition:" + ok.partition() + " offset:" + ok.offset()),
          produceBacklogs());
    }
  }

  private KafkaProducer<String, String> newProducer(Map<String, Object> overrides) {
    Map<String, Object> props = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString());
    props.put(ProducerConfig.RETRIES_CONFIG, 0);
    props.putAll(overrides);
    return new KafkaProducer<>(props, new StringSerializer(), new StringSerializer());
  }

  private static List<String> produceBacklogs() {
    List<String> backlogs = new ArrayList<>();
    for (StatsBucket bucket : dataStreamsPayloads) {
      Collection<Map.Entry<DataStreamsTags, Long>> bucketBacklogs = bucket.getBacklogs();
      if (bucketBacklogs == null) {
        continue;
      }
      for (Map.Entry<DataStreamsTags, Long> backlog : bucketBacklogs) {
        DataStreamsTags tags = backlog.getKey();
        if (tags.hasAllTags(new String[] {"type:kafka_produce"})) {
          backlogs.add(
              tags.getTopic() + " " + tags.getPartition() + " offset:" + backlog.getValue());
        }
      }
    }
    Collections.sort(backlogs);
    return backlogs;
  }

  // Backlogs recorded before this one sit in the same or an earlier bucket, so they are flushed by
  // then.
  private static void waitForProduceBacklog(String topic) throws InterruptedException {
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
    while (System.currentTimeMillis() < deadline) {
      for (String backlog : produceBacklogs()) {
        if (backlog.startsWith("topic:" + topic + " ")) {
          return;
        }
      }
      Thread.sleep(20);
    }
    throw new AssertionError("No kafka_produce backlog reported for " + topic);
  }

  private static String repeat(char c, int count) {
    char[] chars = new char[count];
    Arrays.fill(chars, c);
    return new String(chars);
  }

  private static final class NoopSink implements Sink {
    @Override
    public void accept(int messageCount, ByteBuffer buffer) {}

    @Override
    public void register(EventListener listener) {}
  }

  private static final class DataStreamsEnabledTraceConfig
      implements TraceConfig, Supplier<TraceConfig> {
    @Override
    public TraceConfig get() {
      return this;
    }

    @Override
    public boolean isTraceEnabled() {
      return true;
    }

    @Override
    public boolean isRuntimeMetricsEnabled() {
      return true;
    }

    @Override
    public boolean isLogsInjectionEnabled() {
      return true;
    }

    @Override
    public boolean isDataStreamsEnabled() {
      return true;
    }

    @Override
    public Map<String, String> getServiceMapping() {
      return null;
    }

    @Override
    public Map<String, String> getRequestHeaderTags() {
      return null;
    }

    @Override
    public Map<String, String> getResponseHeaderTags() {
      return null;
    }

    @Override
    public Map<String, String> getBaggageMapping() {
      return null;
    }

    @Override
    public Double getTraceSampleRate() {
      return null;
    }

    @Override
    public Map<String, String> getTracingTags() {
      return null;
    }

    @Override
    public Pair<String, CharSequence> getPreferredServiceNameAndSource() {
      return null;
    }

    @Override
    public List<? extends SpanSamplingRule> getSpanSamplingRules() {
      return null;
    }

    @Override
    public List<? extends TraceSamplingRule> getTraceSamplingRules() {
      return null;
    }

    @Override
    public List<DataStreamsTransactionExtractor> getDataStreamsTransactionExtractors() {
      return null;
    }
  }
}
