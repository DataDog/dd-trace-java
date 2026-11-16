import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.elasticmq.rest.sqs.SQSRestServer;
import org.elasticmq.rest.sqs.SQSRestServerBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.auth.credentials.AnonymousCredentialsProvider;
import software.amazon.awssdk.core.SdkSystemSetting;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/** Data Streams off at startup, then toggled at runtime the way remote config does. */
@WithConfig(key = "data.streams.enabled", value = "false")
class SqsDataStreamsRemoteConfigForkedTest extends AbstractInstrumentationTest {
  private static final String QUEUE = "dsmremoteconfig";
  private static final String PATHWAY_KEY = "dd-pathway-ctx-base64";

  private static final RecordingDatastreamsPayloadWriter dataStreamsWriter =
      new RecordingDatastreamsPayloadWriter();
  private static volatile boolean dataStreamsOn = false;
  private static DefaultDataStreamsMonitoring dataStreamsMonitoring;

  private static SQSRestServer server;
  private static URI endpoint;

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
  static void startSqsServer() {
    System.setProperty(SdkSystemSetting.AWS_ACCESS_KEY_ID.property(), "my-access-key");
    System.setProperty(SdkSystemSetting.AWS_SECRET_ACCESS_KEY.property(), "my-secret-key");
    server = SQSRestServerBuilder.withInterface("localhost").withDynamicPort().start();
    int port = server.waitUntilStarted().localAddress().getPort();
    endpoint = URI.create("http://localhost:" + port);
  }

  @AfterAll
  static void stopSqsServer() {
    if (server != null) {
      server.stopAndWait();
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
  void dataStreamsFollowsTheLiveConfigForClientsBuiltWhileItWasOff() throws Exception {
    try (SqsClient client =
        SqsClient.builder()
            .region(Region.EU_CENTRAL_1)
            .endpointOverride(endpoint)
            .credentialsProvider(AnonymousCredentialsProvider.create())
            .build()) {
      String queueUrl =
          client.createQueue(CreateQueueRequest.builder().queueName(QUEUE).build()).queueUrl();

      // data streams is off
      assertFalse(sendAndReceive(client, queueUrl, "off").contains(PATHWAY_KEY));
      assertEquals(0, settledGroupCount());

      // remote config enables data streams
      toggleDataStreams(true);
      assertTrue(sendAndReceive(client, queueUrl, "on").contains(PATHWAY_KEY));
      new PollingConditions(5)
          .eventually(() -> {
            assertTrue(hasGroup("direction:out", "topic:" + QUEUE, "type:sqs"));
            assertTrue(hasGroup("direction:in", "topic:" + QUEUE, "type:sqs"));
          });

      // remote config disables data streams again
      toggleDataStreams(false);
      int groupsBeforeOffAgain = settledGroupCount();
      assertFalse(sendAndReceive(client, queueUrl, "off again").contains(PATHWAY_KEY));
      // Stats stop too: no new group is written once DSM is off
      assertEquals(groupsBeforeOffAgain, settledGroupCount());
    }
  }

  /** Sends one message and returns its received {@code _datadog} attribute value. */
  private static String sendAndReceive(SqsClient client, String queueUrl, String body) {
    client.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(body).build());
    List<Message> messages =
        client
            .receiveMessage(ReceiveMessageRequest.builder().queueUrl(queueUrl).build())
            .messages();
    // iterating finishes the consume span and sets the inbound checkpoint
    Message received = null;
    for (Message message : messages) {
      received = message;
    }
    assertNotNull(received);
    assertEquals(body, received.body());
    client.deleteMessage(
        DeleteMessageRequest.builder()
            .queueUrl(queueUrl)
            .receiptHandle(received.receiptHandle())
            .build());
    MessageAttributeValue datadogAttribute = received.messageAttributes().get("_datadog");
    assertNotNull(datadogAttribute);
    return datadogAttribute.stringValue();
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
