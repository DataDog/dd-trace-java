import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.datastreams.MockFeaturesDiscovery;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.Pair;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.AgentDataStreamsMonitoring;
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
import datadog.trace.instrumentation.kafka_clients38.OffsetCommitBacklogListener;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.consumer.internals.RequestFuture;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.errors.OffsetMetadataTooLarge;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.test.utils.KafkaTestUtils;

@WithConfig(key = "data.streams.enabled", value = "true")
class KafkaProducerFailureDataStreamsForkedTest extends AbstractInstrumentationTest {
  private static final String OK_TOPIC = "ok.topic";
  private static final String REJECTING_TOPIC = "rejecting.topic";
  private static final Duration COMMIT_TIMEOUT = Duration.ofSeconds(15);

  private static final List<Payload> dataStreamsPayloads = new CopyOnWriteArrayList<>();

  private KafkaTestBroker embeddedKafka;

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
            (data, serviceNameOverride) ->
                dataStreamsPayloads.add(new Payload(data, serviceNameOverride)),
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
    embeddedKafka = new KafkaTestBroker(1, 1, OK_TOPIC);
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

  @Test
  void rejectedClassicCommitDoesNotReportAnUncommittedOffset() throws Exception {
    String rejectedGroup = "rejected-commit";
    String acceptedGroup = "accepted-commit";
    TopicPartition partition = new TopicPartition(OK_TOPIC, 0);
    try (KafkaConsumer<String, String> rejected = newClassicConsumer(rejectedGroup);
        KafkaConsumer<String, String> accepted = newClassicConsumer(acceptedGroup)) {
      rejected.assign(Collections.singleton(partition));
      accepted.assign(Collections.singleton(partition));
      assertThrows(
          OffsetMetadataTooLarge.class,
          () ->
              rejected.commitSync(
                  Collections.singletonMap(
                      partition, new OffsetAndMetadata(99L, repeat('x', 8192))),
                  COMMIT_TIMEOUT));
      assertNull(
          rejected.committed(Collections.singleton(partition), COMMIT_TIMEOUT).get(partition));
      accepted.commitSync(
          Collections.singletonMap(partition, new OffsetAndMetadata(2L)), COMMIT_TIMEOUT);
      waitForCommitBacklog(acceptedGroup, 2L, null);
      assertEquals(Collections.emptyList(), commitBacklogs(rejectedGroup, null));
    }
  }

  @Test
  void commitBacklogPreservesTheSubmittingThreadsService() throws Exception {
    String group = "connector-commit";
    String submittingService = "submitting-connector";
    String completionService = "unrelated-connector";
    TopicPartition partition = new TopicPartition(OK_TOPIC, 0);
    RequestFuture<Void> future = new RequestFuture<>();
    AgentDataStreamsMonitoring monitoring = tracer.getDataStreamsMonitoring();
    monitoring.setThreadServiceName(submittingService);
    try {
      future.addListener(
          new OffsetCommitBacklogListener(
              Collections.singletonMap(partition, new OffsetAndMetadata(5L)), null, group));
    } finally {
      monitoring.clearThreadServiceName();
    }

    String restoredService =
        CompletableFuture.supplyAsync(
                () -> {
                  AgentDataStreamsMonitoring completionMonitoring =
                      tracer.getDataStreamsMonitoring();
                  completionMonitoring.setThreadServiceName(completionService);
                  try {
                    future.complete(null);
                    return completionMonitoring.getThreadServiceName();
                  } finally {
                    completionMonitoring.clearThreadServiceName();
                  }
                })
            .get(COMMIT_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    assertEquals(completionService, restoredService);
    waitForCommitBacklog(group, 5L, submittingService);
  }

  private KafkaConsumer<String, String> newClassicConsumer(String group) {
    return new KafkaConsumer<>(
        KafkaTestUtils.consumerProps(embeddedKafka.getBrokersAsString(), group, "false"),
        new StringDeserializer(),
        new StringDeserializer());
  }

  private static List<Long> commitBacklogs(String group, String serviceName) {
    String[] tags = {
      "type:kafka_commit", "topic:" + OK_TOPIC, "partition:0", "consumer_group:" + group
    };
    return backlogs(serviceName)
        .filter(backlog -> backlog.getKey().hasAllTags(tags))
        .map(Map.Entry::getValue)
        .collect(Collectors.toList());
  }

  private static void waitForCommitBacklog(String group, long offset, String serviceName)
      throws InterruptedException {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      if (commitBacklogs(group, serviceName).contains(offset)) {
        return;
      }
      Thread.sleep(20);
    }
    throw new AssertionError(
        "No kafka_commit backlog reported for " + group + " at offset " + offset);
  }

  private KafkaProducer<String, String> newProducer(Map<String, Object> overrides) {
    Map<String, Object> props = KafkaTestUtils.producerProps(embeddedKafka.getBrokersAsString());
    props.put(ProducerConfig.RETRIES_CONFIG, 0);
    props.putAll(overrides);
    return new KafkaProducer<>(props, new StringSerializer(), new StringSerializer());
  }

  private static List<String> produceBacklogs() {
    String[] tags = {"type:kafka_produce"};
    return backlogs(null)
        .filter(backlog -> backlog.getKey().hasAllTags(tags))
        .map(backlog ->
            backlog.getKey().getTopic()
                + " "
                + backlog.getKey().getPartition()
                + " offset:"
                + backlog.getValue())
        .sorted()
        .collect(Collectors.toList());
  }

  private static Stream<Map.Entry<DataStreamsTags, Long>> backlogs(String serviceName) {
    return dataStreamsPayloads.stream()
        .filter(payload -> serviceName == null || serviceName.equals(payload.serviceName))
        .flatMap(payload -> payload.buckets.stream())
        .flatMap(bucket -> bucket.getBacklogs().stream());
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

  private static final class Payload {
    final Collection<StatsBucket> buckets;
    final String serviceName;

    Payload(Collection<StatsBucket> buckets, String serviceName) {
      this.buckets = buckets;
      this.serviceName = serviceName;
    }
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
