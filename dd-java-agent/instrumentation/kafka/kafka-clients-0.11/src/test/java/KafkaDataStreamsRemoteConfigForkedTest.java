import static datadog.trace.api.datastreams.PathwayContext.PROPAGATION_KEY_BASE64;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.datastreams.MockFeaturesDiscovery;
import datadog.trace.agent.test.datastreams.RecordingDatastreamsPayloadWriter;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.Pair;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.DataStreamsTransactionExtractor;
import datadog.trace.api.datastreams.KafkaConfigReport;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.sampling.SamplingRule.SpanSamplingRule;
import datadog.trace.api.sampling.SamplingRule.TraceSamplingRule;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.Sink;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.datastreams.DefaultDataStreamsMonitoring;
import datadog.trace.core.datastreams.StatsBucket;
import datadog.trace.core.datastreams.StatsGroup;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.PollingConditions;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.rule.KafkaEmbedded;
import org.springframework.kafka.test.utils.KafkaTestUtils;

/** Data Streams off at startup, then toggled at runtime the way remote config does. */
@WithConfig(key = "data.streams.enabled", value = "false")
class KafkaDataStreamsRemoteConfigForkedTest extends AbstractInstrumentationTest {
  private static final String TOPIC = "dsm.remote.config";
  // Frequent metadata updates, which report the pending client configs
  private static final String METADATA_MAX_AGE_MS = "500";

  private static final RecordingDatastreamsPayloadWriter dataStreamsWriter =
      new RecordingDatastreamsPayloadWriter();
  private static volatile boolean dataStreamsOn = false;
  private static DefaultDataStreamsMonitoring dataStreamsMonitoring;

  private KafkaEmbedded embeddedKafka;

  // AbstractInstrumentationTest has no data streams support: swap in a tracer with a recording DSM
  @BeforeAll
  static void installDataStreamsTracer() {
    dataStreamsMonitoring =
        new DefaultDataStreamsMonitoring(
            new NoopSink(),
            new MockFeaturesDiscovery(true),
            SystemTimeSource.INSTANCE,
            new LiveDataStreamsTraceConfig(),
            dataStreamsWriter,
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
  void startBroker() throws Exception {
    dataStreamsWriter.clear();
    dataStreamsMonitoring.clear();
    embeddedKafka = new KafkaEmbedded(1, true, TOPIC);
    embeddedKafka.before();
  }

  @AfterEach
  void stopBroker() throws Exception {
    toggleDataStreams(false);
    if (embeddedKafka != null) {
      embeddedKafka.after();
    }
  }

  @Test
  void dataStreamsFollowsTheLiveConfigForClientsBuiltWhileItWasOff() throws Exception {
    Map<String, Object> producerProps =
        KafkaTestUtils.senderProps(embeddedKafka.getBrokersAsString());
    producerProps.put(ProducerConfig.METADATA_MAX_AGE_CONFIG, METADATA_MAX_AGE_MS);
    Map<String, Object> consumerProps =
        KafkaTestUtils.consumerProps("sender", "false", embeddedKafka);
    consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    consumerProps.put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, METADATA_MAX_AGE_MS);
    try (KafkaProducer<String, String> producer =
            new KafkaProducer<>(producerProps, new StringSerializer(), new StringSerializer());
        KafkaConsumer<String, String> consumer =
            new KafkaConsumer<>(
                consumerProps, new StringDeserializer(), new StringDeserializer())) {
      consumer.assign(Collections.singletonList(new TopicPartition(TOPIC, 0)));

      // data streams is off
      ConsumerRecord<String, String> offRecord = produceAndConsume(producer, consumer, "off");
      assertNull(offRecord.headers().lastHeader(PROPAGATION_KEY_BASE64));
      assertNull(consumeSpan().getTag(InstrumentationTags.KAFKA_CLUSTER_ID));

      // remote config enables data streams
      toggleDataStreams(true);
      ConsumerRecord<String, String> onRecord = produceAndConsume(producer, consumer, "on");
      assertNotNull(onRecord.headers().lastHeader(PROPAGATION_KEY_BASE64));
      assertNotNull(consumeSpan().getTag(InstrumentationTags.KAFKA_CLUSTER_ID));
      new PollingConditions(5)
          .eventually(() -> {
            assertTrue(hasGroup("direction:out", "topic:" + TOPIC, "type:kafka"));
            assertTrue(hasGroup("direction:in", "topic:" + TOPIC, "type:kafka"));
          });
      // Configs captured while DSM was off are kept until a metadata update after the enable
      waitForKafkaConfigReports(consumer);

      // remote config disables data streams again
      toggleDataStreams(false);
      int groupsBeforeOffAgain = settledGroupCount();
      ConsumerRecord<String, String> offAgainRecord =
          produceAndConsume(producer, consumer, "off again");
      assertNull(offAgainRecord.headers().lastHeader(PROPAGATION_KEY_BASE64));
      assertNull(consumeSpan().getTag(InstrumentationTags.KAFKA_CLUSTER_ID));
      // Stats stop too: no new group is written once DSM is off
      assertEquals(groupsBeforeOffAgain, settledGroupCount());
    }
  }

  private static boolean hasGroup(String... tags) {
    for (StatsGroup group : dataStreamsWriter.getGroups()) {
      if (group.getTags().hasAllTags(tags)) {
        return true;
      }
    }
    return false;
  }

  /** Polls so client metadata refreshes, until DSM reports the config of each client type. */
  private static void waitForKafkaConfigReports(KafkaConsumer<String, String> consumer) {
    new PollingConditions(15)
        .eventually(() -> {
          consumer.poll(100);
          Set<String> types = new HashSet<>();
          for (StatsBucket bucket : dataStreamsWriter.getPayloads()) {
            for (KafkaConfigReport report : bucket.getKafkaConfigs()) {
              if (!report.getConfig().isEmpty()) {
                types.add(report.getType());
              }
            }
          }
          assertTrue(types.containsAll(Arrays.asList("kafka_producer", "kafka_consumer")));
        });
  }

  /**
   * Group count once in-flight flushes have landed. Waits a fixed window since it checks for an
   * absence; reports run every 50 ms bucket.
   */
  private static int settledGroupCount() {
    AtomicInteger count = new AtomicInteger();
    new PollingConditions()
        .initialDelay(0.5)
        .eventually(() -> count.set(dataStreamsWriter.getGroups().size()));
    return count.get();
  }

  /** Updates the tracer's dynamic config like remote config, then waits for the DSM monitor. */
  private static void toggleDataStreams(boolean enabled) throws Exception {
    dataStreamsOn = enabled;
    Field dynamicConfig = CoreTracer.class.getDeclaredField("dynamicConfig");
    dynamicConfig.setAccessible(true);
    ((DynamicConfig<?>) dynamicConfig.get(tracer)).current().setDataStreamsEnabled(enabled).apply();
    new PollingConditions(5)
        .eventually(() ->
            assertNotEquals(
                enabled, dataStreamsMonitoring.newPathwayContext() instanceof NoopPathwayContext));
  }

  private static ConsumerRecord<String, String> produceAndConsume(
      KafkaProducer<String, String> producer, KafkaConsumer<String, String> consumer, String value)
      throws Exception {
    writer.clear();
    producer.send(new ProducerRecord<>(TOPIC, 0, null, value)).get();
    AtomicReference<ConsumerRecord<String, String>> consumed = new AtomicReference<>();
    new PollingConditions(10)
        .delay(0)
        .eventually(() -> {
          // iterating to the end finishes the consume span
          for (ConsumerRecord<String, String> record : consumer.poll(100)) {
            if (value.equals(record.value())) {
              consumed.set(record);
            }
          }
          assertNotNull(consumed.get());
        });
    return consumed.get();
  }

  /** The single consume span written since the last {@link #produceAndConsume}. */
  private static DDSpan consumeSpan() {
    new PollingConditions(5).delay(0.02).eventually(() -> assertTrue(!consumeSpans().isEmpty()));
    List<DDSpan> spans = consumeSpans();
    assertEquals(1, spans.size());
    return spans.get(0);
  }

  private static List<DDSpan> consumeSpans() {
    List<DDSpan> spans = new ArrayList<>();
    for (List<DDSpan> trace : writer) {
      for (DDSpan span : trace) {
        if ("kafka.consume".equals(span.getOperationName().toString())) {
          spans.add(span);
        }
      }
    }
    return spans;
  }

  private static final class NoopSink implements Sink {
    @Override
    public void accept(int messageCount, ByteBuffer buffer) {}

    @Override
    public void register(EventListener listener) {}
  }

  /** Reports data streams as enabled only while the test has toggled it on. */
  private static final class LiveDataStreamsTraceConfig
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
      return dataStreamsOn;
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
