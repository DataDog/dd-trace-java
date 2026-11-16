import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.fake.FakeIbmMqTextMessage;
import datadog.trace.agent.test.AbstractInstrumentationTest;
import datadog.trace.agent.test.datastreams.MockFeaturesDiscovery;
import datadog.trace.agent.test.datastreams.RecordingDatastreamsPayloadWriter;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.Pair;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.DataStreamsTransactionExtractor;
import datadog.trace.api.datastreams.NoopPathwayContext;
import datadog.trace.api.sampling.SamplingRule.SpanSamplingRule;
import datadog.trace.api.sampling.SamplingRule.TraceSamplingRule;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.Sink;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.datastreams.DefaultDataStreamsMonitoring;
import datadog.trace.core.datastreams.StatsGroup;
import datadog.trace.test.junit.utils.config.WithConfig;
import datadog.trace.test.util.PollingConditions;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import javax.jms.Connection;
import javax.jms.Message;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Queue;
import javax.jms.Session;
import javax.jms.TextMessage;
import org.apache.activemq.junit.EmbeddedActiveMQBroker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Data Streams off at startup, then toggled at runtime the way remote config does. */
@WithConfig(key = "data.streams.enabled", value = "false")
class JMSDataStreamsRemoteConfigForkedTest extends AbstractInstrumentationTest {
  private static final String QUEUE = "dsmremoteconfig";
  // MessageInjectAdapter replaces '-' in header names, JMS property names cannot contain it
  private static final String PATHWAY_PROPERTY = "dd__dash__pathway__dash__ctx__dash__base64";

  private static final RecordingDatastreamsPayloadWriter dataStreamsWriter =
      new RecordingDatastreamsPayloadWriter();
  private static volatile boolean dataStreamsOn = false;
  private static DefaultDataStreamsMonitoring dataStreamsMonitoring;

  private static EmbeddedActiveMQBroker broker;
  private static Connection connection;

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

  @BeforeAll
  static void startBroker() throws Exception {
    broker = new EmbeddedActiveMQBroker();
    broker.start();
    connection = broker.createConnectionFactory().createConnection();
    connection.start();
  }

  @AfterAll
  static void stopBroker() throws Exception {
    if (connection != null) {
      connection.close();
    }
    if (broker != null) {
      broker.stop();
    }
  }

  @BeforeEach
  void clearDataStreams() {
    dataStreamsWriter.clear();
    dataStreamsMonitoring.clear();
  }

  @AfterEach
  void disableDataStreams() throws Exception {
    toggleDataStreams(false);
  }

  @Test
  void dataStreamsFollowsTheLiveConfigForClientsCreatedWhileItWasOff() throws Exception {
    Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    try {
      Queue queue = session.createQueue(QUEUE);
      MessageProducer producer = session.createProducer(queue);
      MessageConsumer consumer = session.createConsumer(queue);

      // data streams is off
      assertNull(sendAndReceive(producer, consumer, "off"));
      assertEquals(0, settledGroupCount());

      // remote config enables data streams
      toggleDataStreams(true);
      // JMS sets checkpoints on the span only and never injects the pathway, so check stats
      sendAndReceive(producer, consumer, "on");
      new PollingConditions(5)
          .eventually(() -> {
            assertTrue(hasGroup("direction:out", "topic:" + QUEUE, "type:ibmmq"));
            assertTrue(hasGroup("direction:in", "topic:" + QUEUE, "type:ibmmq"));
          });

      // remote config disables data streams again
      toggleDataStreams(false);
      int groupsBeforeOffAgain = settledGroupCount();
      assertNull(sendAndReceive(producer, consumer, "off again"));
      // Stats stop too: no new group is written once DSM is off
      assertEquals(groupsBeforeOffAgain, settledGroupCount());
    } finally {
      session.close();
    }
  }

  /** Sends one message and returns the pathway property of the received copy. */
  private static String sendAndReceive(
      MessageProducer producer, MessageConsumer consumer, String text) throws Exception {
    producer.send(new FakeIbmMqTextMessage(text));
    Message received = consumer.receive(TimeUnit.SECONDS.toMillis(5));
    assertTrue(received instanceof FakeIbmMqTextMessage);
    assertEquals(text, ((TextMessage) received).getText());
    return received.getStringProperty(PATHWAY_PROPERTY);
  }

  private static boolean hasGroup(String... tags) {
    for (StatsGroup group : dataStreamsWriter.getGroups()) {
      if (group.getTags().hasAllTags(tags)) {
        return true;
      }
    }
    return false;
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
