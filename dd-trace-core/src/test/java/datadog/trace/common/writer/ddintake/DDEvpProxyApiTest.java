package datadog.trace.common.writer.ddintake;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT;
import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V4_EVP_PROXY_ENDPOINT;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.api.DDTags;
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.api.intake.TrackType;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.ServiceNameSources;
import datadog.trace.bootstrap.instrumentation.api.Tags;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.common.writer.RemoteMapper;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.GZIPInputStream;
import okhttp3.HttpUrl;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.msgpack.jackson.dataformat.MessagePackFactory;

@Timeout(20)
class DDEvpProxyApiTest extends DDCoreJavaSpecification {

  static final CiVisibilityWellKnownTags WELL_KNOWN_TAGS =
      new CiVisibilityWellKnownTags(
          "my-runtime-id",
          "my-env",
          "my-language",
          "my-runtime-name",
          "my-runtime-version",
          "my-runtime-vendor",
          "my-os-arch",
          "my-os-platform",
          "my-os-version",
          "false");

  static final String INTAKE_SUBDOMAIN = "citestcycle-intake";
  static final ObjectMapper MSG_PACK_MAPPER = new ObjectMapper(new MessagePackFactory());
  static final String DD_EVP_SUBDOMAIN_HEADER = "X-Datadog-EVP-Subdomain";

  @Test
  void testSendingAnEmptyListOfTracesReturnsNoErrors() {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    String evpProxyEndpoint = V2_EVP_PROXY_ENDPOINT;
    String path = buildAgentEvpProxyPath(evpProxyEndpoint, trackType, apiVersion);
    JavaTestHttpServer agentEvpProxy =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.post(
                            path,
                            api -> {
                              if (!"application/msgpack"
                                  .equals(api.getRequest().getContentType())) {
                                api.getResponse()
                                    .status(400)
                                    .send("wrong type: " + api.getRequest().getContentType());
                              } else {
                                api.getResponse().status(200).send();
                              }
                            })));
    DDEvpProxyApi client =
        createEvpProxyApi(
            agentEvpProxy.getAddress().toString(), evpProxyEndpoint, trackType, false);
    Payload payload = prepareTraces(trackType, false, Collections.emptyList());

    try {
      RemoteApi.Response clientResponse = client.sendSerializedTraces(payload);
      assertTrue(clientResponse.success());
      assertTrue(clientResponse.status().isPresent());
      assertEquals(200, clientResponse.status().getAsInt());
      assertEquals(path, agentEvpProxy.getLastRequest().getPath());
      assertEquals(
          INTAKE_SUBDOMAIN, agentEvpProxy.getLastRequest().getHeader(DD_EVP_SUBDOMAIN_HEADER));
    } finally {
      agentEvpProxy.close();
    }
  }

  @Test
  void testRetriesWhenBackendReturns5xx() {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    String evpProxyEndpoint = V2_EVP_PROXY_ENDPOINT;
    String path = buildAgentEvpProxyPath(evpProxyEndpoint, trackType, apiVersion);
    int[] retry = {1};
    JavaTestHttpServer agentEvpProxy =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.post(
                            path,
                            api -> {
                              if (retry[0] < 5) {
                                api.getResponse().status(503).send();
                                retry[0]++;
                              } else {
                                api.getResponse().status(200).send();
                              }
                            })));
    DDEvpProxyApi client =
        createEvpProxyApi(
            agentEvpProxy.getAddress().toString(), evpProxyEndpoint, trackType, false);
    Payload payload = prepareTraces(trackType, false, Collections.emptyList());

    try {
      RemoteApi.Response clientResponse = client.sendSerializedTraces(payload);
      assertTrue(clientResponse.success());
      assertTrue(clientResponse.status().isPresent());
      assertEquals(200, clientResponse.status().getAsInt());
      assertEquals(path, agentEvpProxy.getLastRequest().getPath());
      assertEquals(
          INTAKE_SUBDOMAIN, agentEvpProxy.getLastRequest().getHeader(DD_EVP_SUBDOMAIN_HEADER));
    } finally {
      agentEvpProxy.close();
    }
  }

  @Test
  void testContentIsSentAsMsgpackEmptyTraces() throws IOException {
    runContentIsSentAsMsgpackTest(
        TrackType.CITESTCYCLE, "v2", V2_EVP_PROXY_ENDPOINT, false, emptyList(), emptyMap());
  }

  @Test
  void testContentIsSentAsMsgpackFakeTypeSpan() throws IOException {
    DDSpan span = buildSpan(1L, "fakeType", Collections.singletonMap("service.name", "my-service"));
    span.finish();
    setDurationNano(span, 10L);
    List<List<DDSpan>> traces = singletonList(singletonList(span));

    Map<String, Object> metadata = buildMetadataMap();
    Map<String, Object> content = new TreeMap<>();
    content.put("service", "my-service");
    content.put("name", "fakeOperation");
    content.put("resource", "fakeResource");
    content.put("error", 0);
    content.put("trace_id", 1L);
    content.put("span_id", 1L);
    content.put("parent_id", 0L);
    content.put("start", 1000L);
    content.put("duration", 10L);
    content.put("meta", singletonMap(DDTags.DD_SVC_SRC, ServiceNameSources.MANUAL.toString()));
    content.put("metrics", emptyMap());
    Map<String, Object> event = new TreeMap<>();
    event.put("type", "span");
    event.put("version", 1);
    event.put("content", content);
    Map<String, Object> expectedRequestBody = new TreeMap<>();
    expectedRequestBody.put("version", 1);
    expectedRequestBody.put("metadata", metadata);
    expectedRequestBody.put("events", singletonList(event));

    runContentIsSentAsMsgpackTest(
        TrackType.CITESTCYCLE, "v2", V2_EVP_PROXY_ENDPOINT, false, traces, expectedRequestBody);
  }

  @Test
  void testContentIsSentAsMsgpackTestSpan() throws IOException {
    Map<String, Object> spanTags = new HashMap<>();
    spanTags.put("test_suite_id", 123L);
    spanTags.put("test_module_id", 456L);
    DDSpan span = buildSpan(1L, InternalSpanTypes.TEST, spanTags);
    span.finish();
    setDurationNano(span, 10L);
    List<List<DDSpan>> traces = singletonList(singletonList(span));

    Map<String, Object> metadata = buildMetadataMap();
    Map<String, Object> content = new TreeMap<>();
    content.put("test_suite_id", 123L);
    content.put("test_module_id", 456L);
    content.put("service", "fakeService");
    content.put("name", "fakeOperation");
    content.put("resource", "fakeResource");
    content.put("error", 0);
    content.put("trace_id", 1L);
    content.put("span_id", 1L);
    content.put("parent_id", 0L);
    content.put("start", 1000L);
    content.put("duration", 10L);
    content.put("meta", emptyMap());
    content.put("metrics", emptyMap());
    Map<String, Object> event = new TreeMap<>();
    event.put("type", "test");
    event.put("version", 2);
    event.put("content", content);
    Map<String, Object> expectedRequestBody = new TreeMap<>();
    expectedRequestBody.put("version", 1);
    expectedRequestBody.put("metadata", metadata);
    expectedRequestBody.put("events", singletonList(event));

    runContentIsSentAsMsgpackTest(
        TrackType.CITESTCYCLE, "v2", V2_EVP_PROXY_ENDPOINT, false, traces, expectedRequestBody);
  }

  @Test
  void testContentIsSentAsMsgpackTestSuiteEndSpan() throws IOException {
    Map<String, Object> spanTags = new HashMap<>();
    spanTags.put("test_suite_id", 123L);
    spanTags.put("test_module_id", 456L);
    DDSpan span = buildSpan(1L, InternalSpanTypes.TEST_SUITE_END, spanTags);
    span.finish();
    setDurationNano(span, 10L);
    List<List<DDSpan>> traces = singletonList(singletonList(span));

    Map<String, Object> metadata = buildMetadataMap();
    Map<String, Object> content = new TreeMap<>();
    content.put("test_suite_id", 123L);
    content.put("test_module_id", 456L);
    content.put("service", "fakeService");
    content.put("name", "fakeOperation");
    content.put("resource", "fakeResource");
    content.put("error", 0);
    content.put("start", 1000L);
    content.put("duration", 10L);
    content.put("meta", emptyMap());
    content.put("metrics", emptyMap());
    Map<String, Object> event = new TreeMap<>();
    event.put("type", "test_suite_end");
    event.put("version", 1);
    event.put("content", content);
    Map<String, Object> expectedRequestBody = new TreeMap<>();
    expectedRequestBody.put("version", 1);
    expectedRequestBody.put("metadata", metadata);
    expectedRequestBody.put("events", singletonList(event));

    runContentIsSentAsMsgpackTest(
        TrackType.CITESTCYCLE, "v2", V2_EVP_PROXY_ENDPOINT, false, traces, expectedRequestBody);
  }

  @Test
  void testContentIsSentAsMsgpackTestModuleEndSpanWithCompression() throws IOException {
    Map<String, Object> spanTags = new HashMap<>();
    spanTags.put("test_module_id", 456L);
    DDSpan span = buildSpan(1L, InternalSpanTypes.TEST_MODULE_END, spanTags);
    span.finish();
    setDurationNano(span, 10L);
    List<List<DDSpan>> traces = singletonList(singletonList(span));

    Map<String, Object> metadata = buildMetadataMap();
    Map<String, Object> content = new TreeMap<>();
    content.put("test_module_id", 456L);
    content.put("service", "fakeService");
    content.put("name", "fakeOperation");
    content.put("resource", "fakeResource");
    content.put("error", 0);
    content.put("start", 1000L);
    content.put("duration", 10L);
    content.put("meta", emptyMap());
    content.put("metrics", emptyMap());
    Map<String, Object> event = new TreeMap<>();
    event.put("type", "test_module_end");
    event.put("version", 1);
    event.put("content", content);
    Map<String, Object> expectedRequestBody = new TreeMap<>();
    expectedRequestBody.put("version", 1);
    expectedRequestBody.put("metadata", metadata);
    expectedRequestBody.put("events", singletonList(event));

    runContentIsSentAsMsgpackTest(
        TrackType.CITESTCYCLE, "v2", V4_EVP_PROXY_ENDPOINT, true, traces, expectedRequestBody);
  }

  // --- Helper methods ---

  private void runContentIsSentAsMsgpackTest(
      TrackType trackType,
      String apiVersion,
      String evpProxyEndpoint,
      boolean compressionEnabled,
      List<List<DDSpan>> traces,
      Map<String, Object> expectedRequestBody)
      throws IOException {
    String path = buildAgentEvpProxyPath(evpProxyEndpoint, trackType, apiVersion);
    JavaTestHttpServer agentEvpProxy =
        JavaTestHttpServer.httpServer(
            s -> s.handlers(h -> h.post(path, api -> api.getResponse().send())));
    DDEvpProxyApi client =
        createEvpProxyApi(
            agentEvpProxy.getAddress().toString(), evpProxyEndpoint, trackType, compressionEnabled);
    Payload payload = prepareTraces(trackType, compressionEnabled, traces);

    try {
      client.sendSerializedTraces(payload).status();
      assertEquals("application/msgpack", agentEvpProxy.getLastRequest().getContentType());
      Map<String, Object> actualBody =
          convertMap(agentEvpProxy.getLastRequest().getBody(), compressionEnabled);
      assertDeepEquals(expectedRequestBody, actualBody);
    } finally {
      agentEvpProxy.close();
    }
  }

  private static Map<String, Object> buildMetadataMap() {
    Map<String, Object> star = new TreeMap<>();
    star.put("env", "my-env");
    star.put("runtime-id", "my-runtime-id");
    star.put("language", "my-language");
    star.put(Tags.RUNTIME_NAME, "my-runtime-name");
    star.put(Tags.RUNTIME_VERSION, "my-runtime-version");
    star.put(Tags.RUNTIME_VENDOR, "my-runtime-vendor");
    star.put(Tags.OS_ARCHITECTURE, "my-os-arch");
    star.put(Tags.OS_PLATFORM, "my-os-platform");
    star.put(Tags.OS_VERSION, "my-os-version");
    star.put(DDTags.TEST_IS_USER_PROVIDED_SERVICE, "false");
    Map<String, Object> metadata = new TreeMap<>();
    metadata.put("*", star);
    return metadata;
  }

  private static void setDurationNano(DDSpan span, long duration) {
    try {
      Field field = DDSpan.class.getDeclaredField("durationNano");
      field.setAccessible(true);
      field.setLong(span, duration);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      Assertions.fail("Could not set durationNano: " + e.getMessage());
    }
  }

  static Map<String, Object> convertMap(byte[] bytes, boolean compressionEnabled)
      throws IOException {
    if (compressionEnabled) {
      bytes = decompress(bytes);
    }
    return MSG_PACK_MAPPER.readValue(bytes, new TypeReference<TreeMap<String, Object>>() {});
  }

  static byte[] decompress(byte[] bytes) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (GZIPInputStream zip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
      byte[] buf = new byte[4096];
      int len;
      while ((len = zip.read(buf)) != -1) {
        baos.write(buf, 0, len);
      }
    }
    return baos.toByteArray();
  }

  private DDEvpProxyApi createEvpProxyApi(
      String agentUrl, String evpProxyEndpoint, TrackType trackType, boolean compressionEnabled) {
    return DDEvpProxyApi.builder()
        .agentUrl(HttpUrl.get(agentUrl))
        .evpProxyEndpoint(evpProxyEndpoint)
        .trackType(trackType)
        .compressionEnabled(compressionEnabled)
        .build();
  }

  private RemoteMapper discoverMapper(TrackType trackType, boolean compressionEnabled) {
    DDIntakeMapperDiscovery mapperDiscovery =
        new DDIntakeMapperDiscovery(trackType, WELL_KNOWN_TAGS, compressionEnabled);
    mapperDiscovery.discover();
    return mapperDiscovery.getMapper();
  }

  private String buildAgentEvpProxyPath(
      String evpProxyEndpoint, TrackType trackType, String apiVersion) {
    return "/" + evpProxyEndpoint + "api/" + apiVersion + "/" + trackType.name().toLowerCase();
  }

  private Payload prepareTraces(
      TrackType trackType, boolean compressionEnabled, List<List<DDSpan>> traces) {
    TracesCapture traceCapture = new TracesCapture();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1 << 20, traceCapture));
    RemoteMapper mapper = discoverMapper(trackType, compressionEnabled);
    for (List<DDSpan> trace : traces) {
      packer.format(trace, mapper);
    }
    packer.flush();
    return mapper
        .newPayload()
        .withBody(
            traceCapture.traceCount,
            traces.isEmpty() ? ByteBuffer.allocate(0) : traceCapture.buffer);
  }

  @SuppressWarnings("unchecked")
  static void assertDeepEquals(Object expected, Object actual) {
    if (expected == null && actual == null) {
      return;
    }
    if (expected == null || actual == null) {
      Assertions.fail("Expected " + expected + " but got " + actual);
    }
    if (expected instanceof Map) {
      assertInstanceOf(Map.class, actual, "Expected Map but got " + actual.getClass());
      Map<Object, Object> expectedMap = (Map<Object, Object>) expected;
      Map<Object, Object> actualMap = (Map<Object, Object>) actual;
      assertEquals(expectedMap.size(), actualMap.size(), "Map size mismatch");
      for (Map.Entry<Object, Object> entry : expectedMap.entrySet()) {
        assertTrue(actualMap.containsKey(entry.getKey()), "Missing key: " + entry.getKey());
        assertDeepEquals(entry.getValue(), actualMap.get(entry.getKey()));
      }
    } else if (expected instanceof List) {
      assertInstanceOf(List.class, actual, "Expected List but got " + actual.getClass());
      List<Object> expectedList = (List<Object>) expected;
      List<Object> actualList = (List<Object>) actual;
      assertEquals(expectedList.size(), actualList.size(), "List size mismatch");
      for (int i = 0; i < expectedList.size(); i++) {
        assertDeepEquals(expectedList.get(i), actualList.get(i));
      }
    } else if (expected instanceof Number && actual instanceof Number) {
      if (expected instanceof Float
          || expected instanceof Double
          || actual instanceof Float
          || actual instanceof Double) {
        assertEquals(((Number) expected).doubleValue(), ((Number) actual).doubleValue(), 0.0001);
      } else {
        assertEquals(((Number) expected).longValue(), ((Number) actual).longValue());
      }
    } else {
      assertEquals(expected, actual);
    }
  }

  static class TracesCapture implements ByteBufferConsumer {
    int traceCount;
    ByteBuffer buffer;

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      this.buffer = buffer;
      this.traceCount = messageCount;
    }
  }
}
