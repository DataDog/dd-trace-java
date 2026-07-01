package com.datadog.featureflag;

import static java.util.Collections.singletonMap;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.agent.test.server.http.JavaTestHttpServer.HandlerApi;
import datadog.trace.api.Config;
import datadog.trace.api.IdGenerationStrategy;
import datadog.trace.api.featureflag.FeatureFlaggingGateway;
import datadog.trace.api.featureflag.exposure.Allocation;
import datadog.trace.api.featureflag.exposure.ExposureEvent;
import datadog.trace.api.featureflag.exposure.ExposuresRequest;
import datadog.trace.api.featureflag.exposure.Flag;
import datadog.trace.api.featureflag.exposure.Subject;
import datadog.trace.api.featureflag.exposure.Variant;
import datadog.trace.test.util.PollingConditions;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Random;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okio.Okio;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.tabletest.junit.TableTest;

class ExposureWriterTests {

  private static final String EXPOSURES_ENDPOINT = "/evp_proxy/api/v2/exposures";
  private static final double TIMEOUT_SECONDS = 5;

  private final PollingConditions poll = new PollingConditions(TIMEOUT_SECONDS);
  private Queue<ExposuresRequest> requests;
  private Set<String> failed;
  private JavaTestHttpServer server;
  private SharedCommunicationObjects sharedCommunicationObjects;

  @BeforeEach
  void setUp() {
    requests = new ConcurrentLinkedQueue<>();
    failed = Collections.newSetFromMap(new ConcurrentHashMap<String, Boolean>());
    JsonAdapter<ExposuresRequest> adapter =
        new Moshi.Builder().build().adapter(ExposuresRequest.class);
    server =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h -> h.prefix(EXPOSURES_ENDPOINT, api -> handleExposureRequest(api, adapter))));
    sharedCommunicationObjects = sharedCommunicationObjects(true);
  }

  @AfterEach
  void cleanup() {
    if (server != null) {
      server.close();
    }
  }

  private void handleExposureRequest(HandlerApi api, JsonAdapter<ExposuresRequest> adapter)
      throws Exception {
    ExposuresRequest exposuresRequest =
        adapter.fromJson(
            Okio.buffer(Okio.source(new ByteArrayInputStream(api.getRequest().getBody()))));
    String serviceName = exposuresRequest.context.get("service");
    boolean failForever = "fail-forever".equals(serviceName);
    boolean fail = serviceName.startsWith("fail") && (failed.add(serviceName) || failForever);
    if (fail) {
      api.getResponse().status(500).send("Boom!!!");
    } else {
      requests.add(exposuresRequest);
      api.getResponse().status(200).send("OK");
    }
  }

  @TableTest({
    "service        | env    | version",
    "               |        |        ",
    "'test-service' | 'test' | '23'   ",
    "'test-service' |        | '23'   ",
    "'test-service' | 'test' |        "
  })
  void testExposureEventWrites(String service, String env, String version) throws Exception {
    Config config = mockConfig(service, env, version);
    List<ExposureEvent> exposures = buildExposures(5);

    try (ExposureWriterImpl writer =
        new ExposureWriterImpl(1 << 4, 100, MILLISECONDS, sharedCommunicationObjects, config)) {
      writer.init();
      for (ExposureEvent exposure : exposures) {
        writer.accept(exposure);
      }

      poll.eventually(
          () -> {
            assertFalse(requests.isEmpty());
            for (ExposuresRequest request : requests) {
              assertContext(request.context, service, env, version);
            }
            assertExposures(allExposures(), exposures);
          });
    }
  }

  @Test
  void testLruCache() throws Exception {
    Config config = mockConfig("test-service");
    List<ExposureEvent> exposures = buildExposures(6);

    try (ExposureWriterImpl writer =
        new ExposureWriterImpl(1 << 4, 100, MILLISECONDS, sharedCommunicationObjects, config)) {
      writer.init();
      // populating the cache
      for (ExposureEvent exposure : exposures) {
        writer.accept(exposure);
      }

      // all events are written
      poll.eventually(() -> assertEquals(exposures.size(), allExposures().size()));

      // publishing duplicate events
      for (ExposureEvent exposure : exposures) {
        writer.accept(exposure);
      }

      // no events are written
      MILLISECONDS.sleep(300); // wait until a flush happens
      assertEquals(exposures.size(), allExposures().size());

      // a new event is generated
      writer.accept(buildExposure());

      // oldest event is evicted and the new one is submitted
      poll.eventually(() -> assertEquals(exposures.size() + 1, allExposures().size()));
    }
  }

  @Test
  void testHighLoadScenario() throws Exception {
    Config config = mockConfig("test-service");
    int exposuresPerThread = 100;
    Random random = new Random();
    int threads = Runtime.getRuntime().availableProcessors();
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    List<ExposureEvent> exposures = buildExposures(threads * exposuresPerThread);
    CountDownLatch latch = new CountDownLatch(1);

    try (ExposureWriterImpl writer = new ExposureWriterImpl(sharedCommunicationObjects, config)) {
      writer.init();
      List<Future<Boolean>> futures = new ArrayList<>();
      for (int index = 0; index < exposures.size(); index += exposuresPerThread) {
        List<ExposureEvent> partition =
            exposures.subList(index, Math.min(index + exposuresPerThread, exposures.size()));
        futures.add(
            executor.submit(
                () -> {
                  latch.await();
                  for (ExposureEvent exposure : partition) {
                    MILLISECONDS.sleep(random.nextInt(2));
                    writer.accept(exposure);
                  }
                  return true;
                }));
      }
      latch.countDown(); // start threads

      for (Future<Boolean> future : futures) {
        assertTrue(future.get()); // wait for all threads to finish
      }
      poll.eventually(() -> assertExposures(allExposures(), exposures));
    } finally {
      executor.shutdownNow();
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void testFailuresAreRetried(boolean finallyFail) throws Exception {
    String serviceName = finallyFail ? "fail-forever" : "fail-once";
    Config config = mockConfig(serviceName);

    try (ExposureWriterImpl writer =
        new ExposureWriterImpl(1 << 4, 100, MILLISECONDS, sharedCommunicationObjects, config)) {
      writer.init();
      writer.accept(buildExposure());

      MILLISECONDS.sleep(500); // wait for a flush to happen
      ExposuresRequest found = findRequest(serviceName);
      if (finallyFail) {
        assertNull(found, requests.toString());
      } else {
        poll.eventually(() -> assertNotNull(findRequest(serviceName), requests.toString()));
      }
    }
  }

  @Test
  void testWriterStopsReceivingExposuresIfEvpProxyIsNotAvailable() throws Exception {
    SharedCommunicationObjects sharedCommunicationObjects = sharedCommunicationObjects(false);

    try (ExposureWriterImpl writer =
        new ExposureWriterImpl(sharedCommunicationObjects, Config.get())) {
      writer.init();
      poll.eventually(() -> assertFalse(writer.isSerializerThreadAlive()));

      FeatureFlaggingGateway.dispatch(buildExposure());

      assertEquals(0, writer.queueSize());
    }
  }

  private static Config mockConfig(String serviceName) {
    return mockConfig(serviceName, "test", "0.0.0");
  }

  private static Config mockConfig(String serviceName, String env, String version) {
    Config config = mock(Config.class);
    when(config.getIdGenerationStrategy()).thenReturn(IdGenerationStrategy.fromName("RANDOM"));
    when(config.getServiceName()).thenReturn(serviceName);
    when(config.getEnv()).thenReturn(env);
    when(config.getVersion()).thenReturn(version);
    return config;
  }

  private SharedCommunicationObjects sharedCommunicationObjects(boolean evpProxyAvailable) {
    DDAgentFeaturesDiscovery discovery = mock(DDAgentFeaturesDiscovery.class);
    when(discovery.supportsEvpProxy()).thenReturn(evpProxyAvailable);
    if (evpProxyAvailable) {
      when(discovery.getEvpProxyEndpoint()).thenReturn("/evp_proxy/");
    }

    SharedCommunicationObjects sharedCommunicationObjects = new SharedCommunicationObjects();
    sharedCommunicationObjects.setFeaturesDiscovery(discovery);
    sharedCommunicationObjects.agentUrl = HttpUrl.get(server.getAddress());
    sharedCommunicationObjects.agentHttpClient = new OkHttpClient.Builder().build();
    return sharedCommunicationObjects;
  }

  private static void assertContext(
      Map<String, String> context, String service, String env, String version) {
    assertEquals(service == null ? "unknown" : service, context.get("service"));
    assertOptionalContextValue(context, "env", env);
    assertOptionalContextValue(context, "version", version);
  }

  private static void assertOptionalContextValue(
      Map<String, String> context, String key, String value) {
    if (value == null) {
      assertFalse(context.containsKey(key));
    } else {
      assertEquals(value, context.get(key));
    }
  }

  private ExposuresRequest findRequest(String serviceName) {
    for (ExposuresRequest request : requests) {
      if (serviceName.equals(request.context.get("service"))) {
        return request;
      }
    }
    return null;
  }

  private List<ExposureEvent> allExposures() {
    List<ExposureEvent> exposures = new ArrayList<>();
    for (ExposuresRequest request : requests) {
      exposures.addAll(request.exposures);
    }
    return exposures;
  }

  private static void assertExposures(
      List<ExposureEvent> receivedExposures, List<ExposureEvent> expectedExposures) {
    assertEquals(expectedExposures.size(), receivedExposures.size());
    TreeSet<ExposureEvent> received = new TreeSet<>(ExposureWriterTests::compare);
    received.addAll(receivedExposures);
    assertTrue(received.containsAll(expectedExposures));
  }

  private static int compare(ExposureEvent first, ExposureEvent second) {
    if (first == second) {
      return 0;
    }
    if (first == null) {
      return -1;
    }
    if (second == null) {
      return 1;
    }

    int result = Long.compare(first.timestamp, second.timestamp);
    if (result != 0) {
      return result;
    }

    result = compareNullableString(first.flag == null ? null : first.flag.key, second.flag);
    if (result != 0) {
      return result;
    }

    result =
        compareNullableString(first.variant == null ? null : first.variant.key, second.variant);
    if (result != 0) {
      return result;
    }

    result =
        compareNullableString(
            first.allocation == null ? null : first.allocation.key, second.allocation);
    if (result != 0) {
      return result;
    }

    result = compareNullableString(first.subject == null ? null : first.subject.id, second.subject);
    if (result != 0) {
      return result;
    }

    Map.Entry<String, Object> firstEntry = firstEntry(first.subject);
    Map.Entry<String, Object> secondEntry = firstEntry(second.subject);
    result =
        compareNullableString(
            firstEntry == null ? null : firstEntry.getKey(),
            secondEntry == null ? null : secondEntry.getKey());
    if (result != 0) {
      return result;
    }
    return compareNullableString(
        firstEntry == null ? null : String.valueOf(firstEntry.getValue()),
        secondEntry == null ? null : String.valueOf(secondEntry.getValue()));
  }

  private static int compareNullableString(String first, Flag second) {
    return compareNullableString(first, second == null ? null : second.key);
  }

  private static int compareNullableString(String first, Variant second) {
    return compareNullableString(first, second == null ? null : second.key);
  }

  private static int compareNullableString(String first, Allocation second) {
    return compareNullableString(first, second == null ? null : second.key);
  }

  private static int compareNullableString(String first, Subject second) {
    return compareNullableString(first, second == null ? null : second.id);
  }

  private static int compareNullableString(String first, String second) {
    String firstValue = first == null ? "" : first;
    String secondValue = second == null ? "" : second;
    return firstValue.compareTo(secondValue);
  }

  private static Map.Entry<String, Object> firstEntry(Subject subject) {
    if (subject == null || subject.attributes == null) {
      return null;
    }
    Iterator<Map.Entry<String, Object>> iterator = subject.attributes.entrySet().iterator();
    return iterator.hasNext() ? iterator.next() : null;
  }

  private static List<ExposureEvent> buildExposures(int count) {
    List<ExposureEvent> exposures = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      exposures.add(buildExposure());
    }
    return exposures;
  }

  private static ExposureEvent buildExposure() {
    String id = UUID.randomUUID().toString();
    return new ExposureEvent(
        System.currentTimeMillis(),
        new Allocation("Allocation_" + id),
        new Flag("Flag_" + id),
        new Variant("Variant_" + id),
        new Subject("Subject_" + id, singletonMap("key_" + id, (Object) ("value_" + id))));
  }
}
