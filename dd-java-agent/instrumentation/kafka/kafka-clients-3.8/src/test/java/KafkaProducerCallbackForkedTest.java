import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.datastreams.MockFeaturesDiscovery;
import datadog.trace.agent.test.datastreams.RecordingDatastreamsPayloadWriter;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.Sink;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import datadog.trace.core.datastreams.DefaultDataStreamsMonitoring;
import datadog.trace.core.datastreams.StatsBucket;
import datadog.trace.instrumentation.kafka_clients38.KafkaProducerCallback;
import datadog.trace.instrumentation.kafka_common.MetadataState;
import datadog.trace.test.junit.utils.config.WithConfig;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * A send that starts before the producer's first metadata update has no cluster ID; the callback
 * reads it from the metadata state when the send completes.
 */
@WithConfig(key = "data.streams.enabled", value = "true")
class KafkaProducerCallbackForkedTest extends AbstractInstrumentationTest {
  private static final RecordingDatastreamsPayloadWriter dataStreamsWriter =
      new RecordingDatastreamsPayloadWriter();

  // AbstractInstrumentationTest has no data streams support, so swap in a tracer whose data
  // streams monitoring records the payloads it flushes.
  @BeforeAll
  static void installDataStreamsTracer() {
    DefaultDataStreamsMonitoring dataStreamsMonitoring =
        new DefaultDataStreamsMonitoring(
            new NoopSink(),
            new MockFeaturesDiscovery(true),
            SystemTimeSource.INSTANCE,
            AgentTracer::traceConfig,
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

  @Test
  void callbackTagsTheClusterIdRecordedAfterTheSendStarted() throws Exception {
    MetadataState metadataState = new MetadataState();
    AgentSpan span = startSpan("kafka", "kafka.produce");
    // the send started before the first metadata update, so it has no cluster ID
    KafkaProducerCallback callback =
        new KafkaProducerCallback(null, null, span, null, metadataState);
    assertNull(span.getTag(InstrumentationTags.KAFKA_CLUSTER_ID));

    // the metadata update the send waited for records the cluster ID
    metadataState.clusterId = "test-cluster-id";
    callback.onCompletion(
        new RecordMetadata(new TopicPartition("test-topic", 0), 100L, 0, 0L, 0, 0), null);

    writer.waitForTraces(1);
    List<DDSpan> trace = writer.firstTrace();
    assertEquals(1, trace.size());
    assertEquals("test-cluster-id", trace.get(0).getTag(InstrumentationTags.KAFKA_CLUSTER_ID));
    assertEquals(
        Collections.singletonList(
            "topic:test-topic partition:0 kafka_cluster_id:test-cluster-id offset:100"),
        awaitProduceBacklogs());
  }

  private static List<String> awaitProduceBacklogs() throws InterruptedException {
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(15);
    List<String> backlogs = produceBacklogs();
    while (backlogs.isEmpty() && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
      backlogs = produceBacklogs();
    }
    return backlogs;
  }

  private static List<String> produceBacklogs() {
    List<String> backlogs = new ArrayList<>();
    for (StatsBucket bucket : dataStreamsWriter.getPayloads()) {
      if (bucket.getBacklogs() == null) {
        continue;
      }
      for (Map.Entry<DataStreamsTags, Long> backlog : bucket.getBacklogs()) {
        DataStreamsTags tags = backlog.getKey();
        if (tags.hasAllTags(new String[] {"type:kafka_produce"})) {
          backlogs.add(
              tags.getTopic()
                  + " "
                  + tags.getPartition()
                  + " "
                  + tags.getKafkaClusterId()
                  + " offset:"
                  + backlog.getValue());
        }
      }
    }
    return backlogs;
  }

  private static final class NoopSink implements Sink {
    @Override
    public void accept(int messageCount, ByteBuffer buffer) {}

    @Override
    public void register(EventListener listener) {}
  }
}
