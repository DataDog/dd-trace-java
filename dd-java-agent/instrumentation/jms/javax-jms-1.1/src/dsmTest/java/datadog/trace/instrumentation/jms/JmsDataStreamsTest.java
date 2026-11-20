package datadog.trace.instrumentation.jms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ibm.msgclient.jms.IbmMqTextMessage;
import datadog.instrument.classinject.ClassInjector;
import datadog.trace.agent.test.ClassFileTransformerListener;
import datadog.trace.agent.test.datastreams.MockFeaturesDiscovery;
import datadog.trace.agent.test.datastreams.RecordingDatastreamsPayloadWriter;
import datadog.trace.agent.tooling.AgentInstaller;
import datadog.trace.agent.tooling.TracerInstaller;
import datadog.trace.api.Pair;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.datastreams.DataStreamsTransactionExtractor;
import datadog.trace.api.sampling.SamplingRule.SpanSamplingRule;
import datadog.trace.api.sampling.SamplingRule.TraceSamplingRule;
import datadog.trace.api.time.SystemTimeSource;
import datadog.trace.bootstrap.InstrumentationErrors;
import datadog.trace.common.metrics.EventListener;
import datadog.trace.common.metrics.Sink;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.datastreams.DefaultDataStreamsMonitoring;
import datadog.trace.core.datastreams.StatsGroup;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.jms.Connection;
import javax.jms.MessageConsumer;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import net.bytebuddy.agent.ByteBuddyAgent;
import org.apache.activemq.junit.EmbeddedActiveMQBroker;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public final class JmsDataStreamsTest {
  private static final String PATHWAY_PROPERTY = "dd__dash__pathway__dash__ctx__dash__base64";
  private static final String MESSAGE_TEXT = "a message";
  private static final long BUCKET_DURATION_NANOS = TimeUnit.MILLISECONDS.toNanos(250);
  private static final long RECEIVE_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(10);

  private static final Instrumentation INSTRUMENTATION = ByteBuddyAgent.getInstrumentation();

  private static EmbeddedActiveMQBroker broker;
  private static Connection connection;
  private static ListWriter tracesWriter;
  private static RecordingDatastreamsPayloadWriter dsmWriter;
  private static ClassFileTransformer transformer;

  static {
    System.setProperty("dd.data.streams.enabled", "true");
  }

  @BeforeAll
  static void setUp() throws Exception {
    InstrumentationErrors.resetErrors();
    dsmWriter = new RecordingDatastreamsPayloadWriter();
    TraceConfig traceConfig = new TestTraceConfig();
    Sink sink =
        new Sink() {
          @Override
          public void accept(int messageCount, ByteBuffer buffer) {}

          @Override
          public void register(EventListener listener) {}
        };
    DefaultDataStreamsMonitoring monitoring =
        new DefaultDataStreamsMonitoring(
            sink,
            new MockFeaturesDiscovery(true),
            SystemTimeSource.INSTANCE,
            () -> traceConfig,
            dsmWriter,
            BUCKET_DURATION_NANOS);
    tracesWriter = new ListWriter();
    CoreTracer tracer =
        CoreTracer.builder()
            .writer(tracesWriter)
            .strictTraceWrites(true)
            .dataStreamsMonitoring(monitoring)
            .build();
    TracerInstaller.forceInstallGlobalTracer(tracer);
    ClassInjector.enableClassInjection(INSTRUMENTATION);
    transformer =
        AgentInstaller.installBytebuddyAgent(
            INSTRUMENTATION, true, AgentInstaller.getEnabledSystems(), false,
            new ClassFileTransformerListener());
    broker = new EmbeddedActiveMQBroker();
    broker.start();
    connection = broker.createConnectionFactory().createConnection();
    connection.start();
  }

  @AfterEach
  void tearDown() {
    assertTrue(InstrumentationErrors.noErrors(), InstrumentationErrors::describeErrors);
  }

  @AfterAll
  static void cleanUp() throws Exception {
    connection.close();
    broker.stop();
    if (transformer != null) {
      INSTRUMENTATION.removeTransformer(transformer);
    }
    InstrumentationErrors.resetErrors();
  }

  @Test
  void queueProduceAndConsumeLinksCheckpoints() throws Exception {
    Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    MessageProducer producer =
        producerSession.createProducer(producerSession.createQueue("dsmQueue"));
    Session consumerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    MessageConsumer consumer =
        consumerSession.createConsumer(consumerSession.createQueue("dsmQueue"));
    TextMessage message = producerSession.createTextMessage(MESSAGE_TEXT);

    try {
      producer.send(message);
      TextMessage received = (TextMessage) consumer.receive(RECEIVE_TIMEOUT_MILLIS);
      consumer.receiveNoWait();

      assertNotNull(received);
      assertEquals(MESSAGE_TEXT, received.getText());
      assertNotNull(received.getStringProperty(PATHWAY_PROPERTY));
      tracesWriter.waitForTraces(2);

      StatsGroup outbound = awaitGroup("type:activemq", "direction:out", "topic:dsmQueue");
      StatsGroup inbound = awaitGroup("type:activemq", "direction:in", "topic:dsmQueue");
      assertEquals(0L, outbound.getParentHash());
      assertEquals(outbound.getHash(), inbound.getParentHash());
    } finally {
      producerSession.close();
      consumerSession.close();
    }
  }

  @Test
  void ibmMqMessagesKeepCheckpointsWithoutPathwayProperties() throws Exception {
    Session producerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    MessageProducer producer =
        producerSession.createProducer(producerSession.createQueue("dsmIbmQueue"));
    Session consumerSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
    MessageConsumer consumer =
        consumerSession.createConsumer(consumerSession.createQueue("dsmIbmQueue"));
    TextMessage message = new IbmMqTextMessage();
    message.setText(MESSAGE_TEXT);

    try {
      producer.send(message);
      TextMessage received = (TextMessage) consumer.receive(RECEIVE_TIMEOUT_MILLIS);
      consumer.receiveNoWait();

      assertNotNull(received);
      assertNull(received.getStringProperty(PATHWAY_PROPERTY));
      tracesWriter.waitForTraces(2);

      StatsGroup outbound = awaitGroup("type:ibmmq", "direction:out", "topic:dsmIbmQueue");
      assertEquals(0L, outbound.getParentHash());
    } finally {
      producerSession.close();
      consumerSession.close();
    }
  }

  private static StatsGroup awaitGroup(String... tags) throws InterruptedException {
    long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10);
    while (System.currentTimeMillis() < deadline) {
      for (StatsGroup group : dsmWriter.getGroups()) {
        if (Boolean.TRUE.equals(group.getTags().hasAllTags(tags))) {
          return group;
        }
      }
      Thread.sleep(20);
    }
    throw new AssertionError("No stats group with tags " + String.join(", ", tags)
        + " was written");
  }

  private static final class TestTraceConfig implements TraceConfig {
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
