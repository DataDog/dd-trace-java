package datadog.trace.core.datastreams;

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDTraceCoreInfo;
import datadog.trace.test.junit.utils.config.WithConfigExtension;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okio.BufferedSource;
import okio.GzipSource;
import okio.Okio;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

/**
 * This test class exists because a real integration test is not possible. see
 * DataStreamsIntegrationTest
 */
public class DataStreamsWritingTest extends DDCoreJavaSpecification {

  private static long defaultBucketDurationNanos;

  private static JavaTestHttpServer server;
  private static HttpUrl serverAddress;
  private static List<byte[]> requestBodies;

  @BeforeAll
  static void startServer() {
    defaultBucketDurationNanos = Config.get().getDataStreamsBucketDurationNanoseconds();
    requestBodies = new CopyOnWriteArrayList<>();
    server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.post(
                            DDAgentFeaturesDiscovery.V01_DATASTREAMS_ENDPOINT,
                            api -> {
                              requestBodies.add(api.getRequest().getBody());
                              api.getResponse().status(200).send();
                            })));
    serverAddress = HttpUrl.get(server.getAddress());
  }

  @AfterAll
  static void stopServer() {
    if (server != null) {
      server.close();
    }
  }

  @BeforeEach
  void resetRequestBodies() {
    requestBodies.clear();
  }

  private static void awaitOneRequestBody() throws InterruptedException {
    long deadline = System.currentTimeMillis() + 2000;
    while (requestBodies.size() < 1 && System.currentTimeMillis() < deadline) {
      Thread.sleep(20);
    }
    assertEquals(1, requestBodies.size());
  }

  @Test
  void serviceOverridesSplitBuckets() throws InterruptedException, IOException {
    WellKnownTags wellKnownTags =
        new WellKnownTags(
            "runtimeid", "hostname", "test", Config.get().getServiceName(), "version", "java");
    Config fakeConfig = mock(Config.class);
    when(fakeConfig.getAgentUrl()).thenReturn(serverAddress.toString());
    when(fakeConfig.getWellKnownTags()).thenReturn(wellKnownTags);
    when(fakeConfig.getPrimaryTag()).thenReturn("region-1");

    OkHttpClient testOkhttpClient = OkHttpUtils.buildHttpClient(serverAddress, 5000L);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    SharedCommunicationObjects sharedCommObjects = new SharedCommunicationObjects();
    sharedCommObjects.setFeaturesDiscovery(features);
    sharedCommObjects.agentHttpClient = testOkhttpClient;
    sharedCommObjects.createRemaining(fakeConfig);

    ControllableTimeSource timeSource = new ControllableTimeSource();
    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);
    String serviceNameOverride = "service-name-override";

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            fakeConfig, sharedCommObjects, timeSource, () -> traceConfig);
    dataStreams.start();
    dataStreams.setThreadServiceName(serviceNameOverride);
    dataStreams.add(
        new StatsPoint(
            DataStreamsTags.create(null, null),
            9,
            0,
            10,
            timeSource.getCurrentTimeNanos(),
            0,
            0,
            0,
            serviceNameOverride));
    dataStreams.trackBacklog(
        DataStreamsTags.createWithPartition("kafka_produce", "testTopic", "1", null, null), 130);
    timeSource.advance(defaultBucketDurationNanos);
    // force flush
    dataStreams.report();
    dataStreams.close();
    dataStreams.clearThreadServiceName();

    awaitOneRequestBody();
    GzipSource gzipSource =
        new GzipSource(Okio.source(new ByteArrayInputStream(requestBodies.get(0))));
    BufferedSource bufferedSource = Okio.buffer(gzipSource);
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bufferedSource.inputStream());

    assertEquals(9, unpacker.unpackMapHeader());
    assertEquals("Env", unpacker.unpackString());
    assertEquals("test", unpacker.unpackString());
    assertEquals("Service", unpacker.unpackString());
    assertEquals(serviceNameOverride, unpacker.unpackString());
  }

  @ParameterizedTest(name = "Write bucket to mock server with process tags enabled {0}")
  @ValueSource(booleans = {true, false})
  void writeBucketToMockServer(boolean processTagsEnabled)
      throws InterruptedException, IOException {
    WithConfigExtension.injectSysConfig(
        EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, Boolean.toString(processTagsEnabled));
    ProcessTags.reset(Config.get());

    WellKnownTags wellKnownTags =
        new WellKnownTags(
            "runtimeid", "hostname", "test", Config.get().getServiceName(), "version", "java");
    Config fakeConfig = mock(Config.class);
    when(fakeConfig.getAgentUrl()).thenReturn(serverAddress.toString());
    when(fakeConfig.getWellKnownTags()).thenReturn(wellKnownTags);
    when(fakeConfig.getPrimaryTag()).thenReturn("region-1");

    OkHttpClient testOkhttpClient = OkHttpUtils.buildHttpClient(serverAddress, 5000L);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    SharedCommunicationObjects sharedCommObjects = new SharedCommunicationObjects();
    sharedCommObjects.setFeaturesDiscovery(features);
    sharedCommObjects.agentHttpClient = testOkhttpClient;
    sharedCommObjects.createRemaining(fakeConfig);

    ControllableTimeSource timeSource = new ControllableTimeSource();
    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            fakeConfig, sharedCommObjects, timeSource, () -> traceConfig);
    try {
      dataStreams.start();
      dataStreams.add(
          new StatsPoint(
              DataStreamsTags.create(null, null),
              9,
              0,
              10,
              timeSource.getCurrentTimeNanos(),
              0,
              0,
              0,
              null));
      dataStreams.add(
          new StatsPoint(
              DataStreamsTags.create(
                  "testType", DataStreamsTags.Direction.INBOUND, "testTopic", "testGroup", null),
              1,
              2,
              5,
              timeSource.getCurrentTimeNanos(),
              0,
              0,
              0,
              null));
      dataStreams.trackBacklog(
          DataStreamsTags.createWithPartition("kafka_produce", "testTopic", "1", null, null), 100);
      dataStreams.trackBacklog(
          DataStreamsTags.createWithPartition("kafka_produce", "testTopic", "1", null, null), 130);
      timeSource.advance(defaultBucketDurationNanos - 100L);
      dataStreams.add(
          new StatsPoint(
              DataStreamsTags.create(
                  "testType", DataStreamsTags.Direction.INBOUND, "testTopic", "testGroup", null),
              1,
              2,
              5,
              timeSource.getCurrentTimeNanos(),
              SECONDS.toNanos(10),
              SECONDS.toNanos(10),
              10,
              null));
      timeSource.advance(defaultBucketDurationNanos);
      dataStreams.add(
          new StatsPoint(
              DataStreamsTags.create(
                  "testType", DataStreamsTags.Direction.INBOUND, "testTopic", "testGroup", null),
              1,
              2,
              5,
              timeSource.getCurrentTimeNanos(),
              SECONDS.toNanos(5),
              SECONDS.toNanos(5),
              5,
              null));
      dataStreams.add(
          new StatsPoint(
              DataStreamsTags.create(
                  "testType", DataStreamsTags.Direction.INBOUND, "testTopic2", "testGroup", null),
              3,
              4,
              6,
              timeSource.getCurrentTimeNanos(),
              SECONDS.toNanos(2),
              0,
              2,
              null));
      timeSource.advance(defaultBucketDurationNanos);
      dataStreams.close();

      awaitOneRequestBody();
      validateMessage(requestBodies.get(0), processTagsEnabled);
    } finally {
      // cleanup
      WithConfigExtension.injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "true");
      ProcessTags.reset(Config.get());
    }
  }

  @Test
  void writeKafkaConfigsToMockServer() throws InterruptedException, IOException {
    WellKnownTags wellKnownTags =
        new WellKnownTags(
            "runtimeid", "hostname", "test", Config.get().getServiceName(), "version", "java");
    Config fakeConfig = mock(Config.class);
    when(fakeConfig.getAgentUrl()).thenReturn(serverAddress.toString());
    when(fakeConfig.getWellKnownTags()).thenReturn(wellKnownTags);
    when(fakeConfig.getPrimaryTag()).thenReturn("region-1");

    OkHttpClient testOkhttpClient = OkHttpUtils.buildHttpClient(serverAddress, 5000L);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    SharedCommunicationObjects sharedCommObjects = new SharedCommunicationObjects();
    sharedCommObjects.setFeaturesDiscovery(features);
    sharedCommObjects.agentHttpClient = testOkhttpClient;
    sharedCommObjects.createRemaining(fakeConfig);

    ControllableTimeSource timeSource = new ControllableTimeSource();
    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            fakeConfig, sharedCommObjects, timeSource, () -> traceConfig);
    dataStreams.start();

    // Report a producer and consumer config
    Map<String, String> producerConfig = new HashMap<>();
    producerConfig.put("bootstrap.servers", "localhost:9092");
    producerConfig.put("acks", "all");
    producerConfig.put("linger.ms", "5");
    dataStreams.reportKafkaConfig("kafka_producer", "", "", producerConfig);

    Map<String, String> consumerConfig = new HashMap<>();
    consumerConfig.put("bootstrap.servers", "localhost:9092");
    consumerConfig.put("group.id", "test-group");
    consumerConfig.put("auto.offset.reset", "earliest");
    dataStreams.reportKafkaConfig("kafka_consumer", "", "test-group", consumerConfig);

    // Also add a stats point so the bucket is not empty of stats
    dataStreams.add(
        new StatsPoint(
            DataStreamsTags.create(null, null),
            9,
            0,
            10,
            timeSource.getCurrentTimeNanos(),
            0,
            0,
            0,
            null));

    timeSource.advance(defaultBucketDurationNanos);
    dataStreams.close();

    awaitOneRequestBody();
    validateKafkaConfigMessage(requestBodies.get(0));
  }

  @Test
  void duplicateKafkaConfigsAreEachSerializedInPayload() throws InterruptedException, IOException {
    WellKnownTags wellKnownTags =
        new WellKnownTags(
            "runtimeid", "hostname", "test", Config.get().getServiceName(), "version", "java");
    Config fakeConfig = mock(Config.class);
    when(fakeConfig.getAgentUrl()).thenReturn(serverAddress.toString());
    when(fakeConfig.getWellKnownTags()).thenReturn(wellKnownTags);
    when(fakeConfig.getPrimaryTag()).thenReturn("region-1");

    OkHttpClient testOkhttpClient = OkHttpUtils.buildHttpClient(serverAddress, 5000L);
    DDAgentFeaturesDiscovery features = mock(DDAgentFeaturesDiscovery.class);
    when(features.supportsDataStreams()).thenReturn(true);
    SharedCommunicationObjects sharedCommObjects = new SharedCommunicationObjects();
    sharedCommObjects.setFeaturesDiscovery(features);
    sharedCommObjects.agentHttpClient = testOkhttpClient;
    sharedCommObjects.createRemaining(fakeConfig);

    ControllableTimeSource timeSource = new ControllableTimeSource();
    TraceConfig traceConfig = mock(TraceConfig.class);
    when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

    DefaultDataStreamsMonitoring dataStreams =
        new DefaultDataStreamsMonitoring(
            fakeConfig, sharedCommObjects, timeSource, () -> traceConfig);
    dataStreams.start();

    // Report the same producer config twice — both should be serialized
    Map<String, String> producerConfig = new HashMap<>();
    producerConfig.put("bootstrap.servers", "localhost:9092");
    producerConfig.put("acks", "all");
    dataStreams.reportKafkaConfig("kafka_producer", "", "", producerConfig);
    dataStreams.reportKafkaConfig("kafka_producer", "", "", producerConfig);

    // Also add a stats point so the bucket has content
    dataStreams.add(
        new StatsPoint(
            DataStreamsTags.create(null, null),
            9,
            0,
            10,
            timeSource.getCurrentTimeNanos(),
            0,
            0,
            0,
            null));

    timeSource.advance(defaultBucketDurationNanos);
    dataStreams.close();

    awaitOneRequestBody();
    validateDuplicateKafkaConfigMessage(requestBodies.get(0));
  }

  private void validateKafkaConfigMessage(byte[] message) throws IOException {
    GzipSource gzipSource = new GzipSource(Okio.source(new ByteArrayInputStream(message)));
    BufferedSource bufferedSource = Okio.buffer(gzipSource);
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bufferedSource.inputStream());

    // Outer map (same structure as other payloads)
    int outerMapSize = unpacker.unpackMapHeader();
    // Skip to Stats array
    boolean foundStats = false;
    for (int i = 0; i < outerMapSize; i++) {
      String key = unpacker.unpackString();
      if ("Stats".equals(key)) {
        foundStats = true;
        int numBuckets = unpacker.unpackArrayHeader();
        assertTrue(numBuckets >= 1);

        // Parse first bucket
        int bucketMapSize = unpacker.unpackMapHeader();
        boolean foundConfigs = false;
        for (int j = 0; j < bucketMapSize; j++) {
          String bucketKey = unpacker.unpackString();
          if ("Configs".equals(bucketKey)) {
            foundConfigs = true;
            int numConfigs = unpacker.unpackArrayHeader();
            assertEquals(2, numConfigs);

            // Collect configs in a map keyed by type
            Map<String, Map<String, String>> configsByType = new HashMap<>();
            for (int n = 0; n < numConfigs; n++) {
              assertEquals(4, unpacker.unpackMapHeader());
              assertEquals("Type", unpacker.unpackString());
              String type = unpacker.unpackString();
              assertEquals("KafkaClusterId", unpacker.unpackString());
              unpacker.unpackString(); // skip cluster id value
              assertEquals("ConsumerGroup", unpacker.unpackString());
              unpacker.unpackString(); // skip consumer group value
              assertEquals("Config", unpacker.unpackString());
              int configSize = unpacker.unpackMapHeader();
              Map<String, String> configEntries = new HashMap<>();
              for (int c = 0; c < configSize; c++) {
                String ck = unpacker.unpackString();
                String cv = unpacker.unpackString();
                configEntries.put(ck, cv);
              }
              configsByType.put(type, configEntries);
            }

            // Verify producer config
            assertTrue(configsByType.containsKey("kafka_producer"));
            assertEquals(
                "localhost:9092", configsByType.get("kafka_producer").get("bootstrap.servers"));
            assertEquals("all", configsByType.get("kafka_producer").get("acks"));
            assertEquals("5", configsByType.get("kafka_producer").get("linger.ms"));

            // Verify consumer config
            assertTrue(configsByType.containsKey("kafka_consumer"));
            assertEquals(
                "localhost:9092", configsByType.get("kafka_consumer").get("bootstrap.servers"));
            assertEquals("test-group", configsByType.get("kafka_consumer").get("group.id"));
            assertEquals("earliest", configsByType.get("kafka_consumer").get("auto.offset.reset"));
          } else {
            unpacker.skipValue();
          }
        }
        assertTrue(foundConfigs, "Configs field not found in bucket");

        // Skip remaining buckets
        for (int b = 1; b < numBuckets; b++) {
          unpacker.skipValue();
        }
      } else {
        unpacker.skipValue();
      }
    }
    assertTrue(foundStats, "Stats field not found in payload");
  }

  private void validateDuplicateKafkaConfigMessage(byte[] message) throws IOException {
    GzipSource gzipSource = new GzipSource(Okio.source(new ByteArrayInputStream(message)));
    BufferedSource bufferedSource = Okio.buffer(gzipSource);
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bufferedSource.inputStream());

    int outerMapSize = unpacker.unpackMapHeader();
    boolean foundStats = false;
    for (int i = 0; i < outerMapSize; i++) {
      String key = unpacker.unpackString();
      if ("Stats".equals(key)) {
        foundStats = true;
        int numBuckets = unpacker.unpackArrayHeader();
        assertTrue(numBuckets >= 1);

        // Parse first bucket
        int bucketMapSize = unpacker.unpackMapHeader();
        boolean foundConfigs = false;
        for (int j = 0; j < bucketMapSize; j++) {
          String bucketKey = unpacker.unpackString();
          if ("Configs".equals(bucketKey)) {
            foundConfigs = true;
            int numConfigs = unpacker.unpackArrayHeader();
            // Both configs should be present (no deduplication)
            assertEquals(2, numConfigs);

            for (int n = 0; n < numConfigs; n++) {
              assertEquals(4, unpacker.unpackMapHeader());
              assertEquals("Type", unpacker.unpackString());
              assertEquals("kafka_producer", unpacker.unpackString());
              assertEquals("KafkaClusterId", unpacker.unpackString());
              unpacker.unpackString(); // skip cluster id value
              assertEquals("ConsumerGroup", unpacker.unpackString());
              unpacker.unpackString(); // skip consumer group value
              assertEquals("Config", unpacker.unpackString());
              int configSize = unpacker.unpackMapHeader();
              Map<String, String> configEntries = new HashMap<>();
              for (int c = 0; c < configSize; c++) {
                String ck = unpacker.unpackString();
                String cv = unpacker.unpackString();
                configEntries.put(ck, cv);
              }
              assertEquals("localhost:9092", configEntries.get("bootstrap.servers"));
              assertEquals("all", configEntries.get("acks"));
            }
          } else {
            unpacker.skipValue();
          }
        }
        assertTrue(foundConfigs, "Configs field not found in bucket");

        for (int b = 1; b < numBuckets; b++) {
          unpacker.skipValue();
        }
      } else {
        unpacker.skipValue();
      }
    }
    assertTrue(foundStats, "Stats field not found in payload");
  }

  private void validateMessage(byte[] message, boolean processTagsEnabled) throws IOException {
    GzipSource gzipSource = new GzipSource(Okio.source(new ByteArrayInputStream(message)));
    BufferedSource bufferedSource = Okio.buffer(gzipSource);
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bufferedSource.inputStream());

    assertEquals(8 + (processTagsEnabled ? 1 : 0), unpacker.unpackMapHeader());
    assertEquals("Env", unpacker.unpackString());
    assertEquals("test", unpacker.unpackString());
    assertEquals("Service", unpacker.unpackString());
    assertEquals(Config.get().getServiceName(), unpacker.unpackString());
    assertEquals("Lang", unpacker.unpackString());
    assertEquals("java", unpacker.unpackString());
    assertEquals("PrimaryTag", unpacker.unpackString());
    assertEquals("region-1", unpacker.unpackString());
    assertEquals("TracerVersion", unpacker.unpackString());
    assertEquals(DDTraceCoreInfo.VERSION, unpacker.unpackString());
    assertEquals("Version", unpacker.unpackString());
    assertEquals("version", unpacker.unpackString());
    assertEquals("Stats", unpacker.unpackString());
    assertEquals(2, unpacker.unpackArrayHeader()); // 2 time buckets

    // FIRST BUCKET
    assertEquals(4, unpacker.unpackMapHeader());
    assertEquals("Start", unpacker.unpackString());
    unpacker.skipValue();
    assertEquals("Duration", unpacker.unpackString());
    assertEquals(defaultBucketDurationNanos, unpacker.unpackLong());
    assertEquals("Stats", unpacker.unpackString());
    assertEquals(2, unpacker.unpackArrayHeader()); // 2 groups in first bucket

    // we don't know the order the groups will be reported
    Set<Integer> availableSizes = new HashSet<>();
    availableSizes.add(5);
    availableSizes.add(6);
    for (int g = 0; g < 2; g++) {
      int mapHeaderSize = unpacker.unpackMapHeader();
      assertTrue(availableSizes.remove(mapHeaderSize));
      if (mapHeaderSize == 5) {
        // empty topic group
        assertEquals("PathwayLatency", unpacker.unpackString());
        unpacker.skipValue();
        assertEquals("EdgeLatency", unpacker.unpackString());
        unpacker.skipValue();
        assertEquals("PayloadSize", unpacker.unpackString());
        unpacker.skipValue();
        assertEquals("Hash", unpacker.unpackString());
        assertEquals(9L, unpacker.unpackLong());
        assertEquals("ParentHash", unpacker.unpackString());
        assertEquals(0L, unpacker.unpackLong());
      } else {
        // other group
        assertEquals("PathwayLatency", unpacker.unpackString());
        unpacker.skipValue();
        assertEquals("EdgeLatency", unpacker.unpackString());
        unpacker.skipValue();
        assertEquals("PayloadSize", unpacker.unpackString());
        unpacker.skipValue();
        assertEquals("Hash", unpacker.unpackString());
        assertEquals(1L, unpacker.unpackLong());
        assertEquals("ParentHash", unpacker.unpackString());
        assertEquals(2L, unpacker.unpackLong());
        assertEquals("EdgeTags", unpacker.unpackString());
        assertEquals(4, unpacker.unpackArrayHeader());
        assertEquals("direction:in", unpacker.unpackString());
        assertEquals("topic:testTopic", unpacker.unpackString());
        assertEquals("type:testType", unpacker.unpackString());
        assertEquals("group:testGroup", unpacker.unpackString());
      }
    }

    // Kafka stats
    assertEquals("Backlogs", unpacker.unpackString());
    assertEquals(1, unpacker.unpackArrayHeader());
    assertEquals(2, unpacker.unpackMapHeader());
    assertEquals("Tags", unpacker.unpackString());
    assertEquals(3, unpacker.unpackArrayHeader());
    assertEquals("topic:testTopic", unpacker.unpackString());
    assertEquals("type:kafka_produce", unpacker.unpackString());
    assertEquals("partition:1", unpacker.unpackString());
    assertEquals("Value", unpacker.unpackString());
    assertEquals(130L, unpacker.unpackLong());

    // SECOND BUCKET
    assertEquals(3, unpacker.unpackMapHeader());
    assertEquals("Start", unpacker.unpackString());
    unpacker.skipValue();
    assertEquals("Duration", unpacker.unpackString());
    assertEquals(defaultBucketDurationNanos, unpacker.unpackLong());
    assertEquals("Stats", unpacker.unpackString());
    assertEquals(2, unpacker.unpackArrayHeader()); // 2 groups in second bucket

    // we don't know the order the groups will be reported
    Set<Long> availableHashes = new HashSet<>();
    availableHashes.add(1L);
    availableHashes.add(3L);
    for (int g = 0; g < 2; g++) {
      assertEquals(6, unpacker.unpackMapHeader());
      assertEquals("PathwayLatency", unpacker.unpackString());
      unpacker.skipValue();
      assertEquals("EdgeLatency", unpacker.unpackString());
      unpacker.skipValue();
      assertEquals("PayloadSize", unpacker.unpackString());
      unpacker.skipValue();
      assertEquals("Hash", unpacker.unpackString());
      long hash = unpacker.unpackLong();
      assertTrue(availableHashes.remove(hash));
      assertEquals("ParentHash", unpacker.unpackString());
      assertEquals(hash == 1L ? 2L : 4L, unpacker.unpackLong());
      assertEquals("EdgeTags", unpacker.unpackString());
      assertEquals(4, unpacker.unpackArrayHeader());
      assertEquals("direction:in", unpacker.unpackString());
      assertEquals(hash == 1L ? "topic:testTopic" : "topic:testTopic2", unpacker.unpackString());
      assertEquals("type:testType", unpacker.unpackString());
      assertEquals("group:testGroup", unpacker.unpackString());
    }

    assertEquals("ProductMask", unpacker.unpackString());
    assertEquals(1L, unpacker.unpackLong());

    List<String> processTags = ProcessTags.getTagsAsStringList();
    if (processTags == null) {
      assertFalse(unpacker.hasNext());
    } else {
      assertTrue(unpacker.hasNext());
      assertEquals("ProcessTags", unpacker.unpackString());
      assertEquals(processTags.size(), unpacker.unpackArrayHeader());
      for (String tag : processTags) {
        assertEquals(tag, unpacker.unpackString());
      }
    }
  }
}
