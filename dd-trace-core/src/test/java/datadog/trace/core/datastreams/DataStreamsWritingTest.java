package datadog.trace.core.datastreams;

import static datadog.trace.api.config.GeneralConfig.EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.communication.http.OkHttpUtils;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.TraceConfig;
import datadog.trace.api.WellKnownTags;
import datadog.trace.api.datastreams.DataStreamsTags;
import datadog.trace.api.datastreams.StatsPoint;
import datadog.trace.api.time.ControllableTimeSource;
import datadog.trace.core.DDTraceCoreInfo;
import datadog.trace.core.test.DDCoreSpecification;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import okhttp3.HttpUrl;
import okio.BufferedSource;
import okio.GzipSource;
import okio.Okio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;

/**
 * This test class exists because a real integration test is not possible. see
 * DataStreamsIntegrationTest
 */
class DataStreamsWritingTest extends DDCoreSpecification {

  static final long DEFAULT_BUCKET_DURATION_NANOS =
      Config.get().getDataStreamsBucketDurationNanoseconds();

  private HttpServer server;
  private List<byte[]> requestBodies;

  @BeforeEach
  void setupServer() throws IOException {
    requestBodies = new ArrayList<>();
    server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
    server.createContext(
        "/" + DDAgentFeaturesDiscovery.V01_DATASTREAMS_ENDPOINT,
        new HttpHandler() {
          @Override
          public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equals(exchange.getRequestMethod())) {
              InputStream in = exchange.getRequestBody();
              ByteArrayOutputStream baos = new ByteArrayOutputStream();
              byte[] buf = new byte[4096];
              int n;
              while ((n = in.read(buf)) != -1) {
                baos.write(buf, 0, n);
              }
              synchronized (requestBodies) {
                requestBodies.add(baos.toByteArray());
              }
              exchange.sendResponseHeaders(200, -1);
            } else {
              exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
          }
        });
    server.start();
  }

  @AfterEach
  void tearDownServer() {
    if (server != null) {
      server.stop(0);
    }
  }

  private String serverAddress() {
    return "http://localhost:" + server.getAddress().getPort();
  }

  private static void awaitCondition(long timeoutMillis, ConditionChecker condition)
      throws Exception {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (condition.check()) return;
      Thread.sleep(50);
    }
    assertTrue(condition.check(), "Condition not met within timeout");
  }

  @FunctionalInterface
  interface ConditionChecker {
    boolean check() throws Exception;
  }

  @Test
  void serviceOverridesSplitBuckets() throws Exception {
    okhttp3.OkHttpClient testOkhttpClient =
        OkHttpUtils.buildHttpClient(HttpUrl.get(serverAddress()), 5000L);

    DDAgentFeaturesDiscovery features = org.mockito.Mockito.mock(DDAgentFeaturesDiscovery.class);
    org.mockito.Mockito.when(features.supportsDataStreams()).thenReturn(true);

    WellKnownTags wellKnownTags =
        new WellKnownTags(
            "runtimeid", "hostname", "test", Config.get().getServiceName(), "version", "java");

    Config fakeConfig = org.mockito.Mockito.mock(Config.class);
    org.mockito.Mockito.when(fakeConfig.getAgentUrl()).thenReturn(serverAddress());
    org.mockito.Mockito.when(fakeConfig.getWellKnownTags()).thenReturn(wellKnownTags);
    org.mockito.Mockito.when(fakeConfig.getPrimaryTag()).thenReturn("region-1");

    SharedCommunicationObjects sharedCommObjects = new SharedCommunicationObjects();
    sharedCommObjects.setFeaturesDiscovery(features);
    sharedCommObjects.agentHttpClient = testOkhttpClient;
    sharedCommObjects.createRemaining(fakeConfig);

    ControllableTimeSource timeSource = new ControllableTimeSource();

    TraceConfig traceConfig = org.mockito.Mockito.mock(TraceConfig.class);
    org.mockito.Mockito.when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

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
    timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
    // force flush
    dataStreams.report();
    dataStreams.close();
    dataStreams.clearThreadServiceName();

    awaitCondition(
        2000,
        () -> {
          synchronized (requestBodies) {
            return requestBodies.size() == 1;
          }
        });

    byte[] body;
    synchronized (requestBodies) {
      body = requestBodies.get(0);
    }

    GzipSource gzipSource = new GzipSource(Okio.source(new ByteArrayInputStream(body)));
    BufferedSource bufferedSource = Okio.buffer(gzipSource);
    MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(bufferedSource.inputStream());

    assertEquals(8, unpacker.unpackMapHeader());
    assertEquals("Env", unpacker.unpackString());
    assertEquals("test", unpacker.unpackString());
    assertEquals("Service", unpacker.unpackString());
    assertEquals(serviceNameOverride, unpacker.unpackString());
  }

  static Stream<Arguments> writeBucketToMockServerWithProcessTagsEnabledArguments() {
    return Stream.of(Arguments.of(true), Arguments.of(false));
  }

  @ParameterizedTest
  @MethodSource("writeBucketToMockServerWithProcessTagsEnabledArguments")
  void writeBucketToMockServerWithProcessTagsEnabled(boolean processTagsEnabled) throws Exception {
    injectSysConfig(
        EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, String.valueOf(processTagsEnabled));
    ProcessTags.reset(Config.get());

    try {
      okhttp3.OkHttpClient testOkhttpClient =
          OkHttpUtils.buildHttpClient(HttpUrl.get(serverAddress()), 5000L);

      DDAgentFeaturesDiscovery features = org.mockito.Mockito.mock(DDAgentFeaturesDiscovery.class);
      org.mockito.Mockito.when(features.supportsDataStreams()).thenReturn(true);

      WellKnownTags wellKnownTags =
          new WellKnownTags(
              "runtimeid", "hostname", "test", Config.get().getServiceName(), "version", "java");

      Config fakeConfig = org.mockito.Mockito.mock(Config.class);
      org.mockito.Mockito.when(fakeConfig.getAgentUrl()).thenReturn(serverAddress());
      org.mockito.Mockito.when(fakeConfig.getWellKnownTags()).thenReturn(wellKnownTags);
      org.mockito.Mockito.when(fakeConfig.getPrimaryTag()).thenReturn("region-1");

      SharedCommunicationObjects sharedCommObjects = new SharedCommunicationObjects();
      sharedCommObjects.setFeaturesDiscovery(features);
      sharedCommObjects.agentHttpClient = testOkhttpClient;
      sharedCommObjects.createRemaining(fakeConfig);

      ControllableTimeSource timeSource = new ControllableTimeSource();

      TraceConfig traceConfig = org.mockito.Mockito.mock(TraceConfig.class);
      org.mockito.Mockito.when(traceConfig.isDataStreamsEnabled()).thenReturn(true);

      DefaultDataStreamsMonitoring dataStreams =
          new DefaultDataStreamsMonitoring(
              fakeConfig, sharedCommObjects, timeSource, () -> traceConfig);
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
      timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS - 100L);
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
      timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
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
      timeSource.advance(DEFAULT_BUCKET_DURATION_NANOS);
      dataStreams.close();

      awaitCondition(
          2000,
          () -> {
            synchronized (requestBodies) {
              return requestBodies.size() == 1;
            }
          });

      byte[] body;
      synchronized (requestBodies) {
        body = requestBodies.get(0);
      }
      validateMessage(body, processTagsEnabled);
    } finally {
      injectSysConfig(EXPERIMENTAL_PROPAGATE_PROCESS_TAGS_ENABLED, "true");
      ProcessTags.reset(Config.get());
    }
  }

  private void validateMessage(byte[] message, boolean processTagsEnabled) throws Exception {
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
    assertEquals(DEFAULT_BUCKET_DURATION_NANOS, unpacker.unpackLong());
    assertEquals("Stats", unpacker.unpackString());
    assertEquals(2, unpacker.unpackArrayHeader()); // 2 groups in first bucket

    Set<Integer> availableSizes = new HashSet<>();
    availableSizes.add(5);
    availableSizes.add(6);
    for (int i = 0; i < 2; i++) {
      int mapHeaderSize = unpacker.unpackMapHeader();
      assertTrue(
          availableSizes.remove(mapHeaderSize), "Unexpected map header size: " + mapHeaderSize);
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
    assertEquals(DEFAULT_BUCKET_DURATION_NANOS, unpacker.unpackLong());
    assertEquals("Stats", unpacker.unpackString());
    assertEquals(2, unpacker.unpackArrayHeader()); // 2 groups in second bucket

    Set<Long> availableHashes = new HashSet<>();
    availableHashes.add(1L);
    availableHashes.add(3L);
    for (int i = 0; i < 2; i++) {
      assertEquals(6, unpacker.unpackMapHeader());
      assertEquals("PathwayLatency", unpacker.unpackString());
      unpacker.skipValue();
      assertEquals("EdgeLatency", unpacker.unpackString());
      unpacker.skipValue();
      assertEquals("PayloadSize", unpacker.unpackString());
      unpacker.skipValue();
      assertEquals("Hash", unpacker.unpackString());
      long hash = unpacker.unpackLong();
      assertTrue(availableHashes.remove(hash), "Unexpected hash: " + hash);
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
    assertEquals(processTags != null, unpacker.hasNext());
    if (processTags != null) {
      assertEquals("ProcessTags", unpacker.unpackString());
      assertEquals(processTags.size(), unpacker.unpackArrayHeader());
      for (String tag : processTags) {
        assertEquals(tag, unpacker.unpackString());
      }
    }
  }
}
