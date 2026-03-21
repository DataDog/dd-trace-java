package datadog.trace.common.writer.ddintake;

import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V2_EVP_PROXY_ENDPOINT;
import static datadog.communication.ddagent.DDAgentFeaturesDiscovery.V4_EVP_PROXY_ENDPOINT;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.trace.api.civisibility.CiVisibilityWellKnownTags;
import datadog.trace.api.intake.TrackType;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.common.writer.Payload;
import datadog.trace.core.DDSpan;
import datadog.trace.core.test.DDCoreSpecification;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.GZIPInputStream;
import okhttp3.HttpUrl;
import org.apache.commons.io.IOUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.msgpack.jackson.dataformat.MessagePackFactory;

@Timeout(20)
class DDEvpProxyApiTest extends DDCoreSpecification {

  static CiVisibilityWellKnownTags wellKnownTags =
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

  static String intakeSubdomain = "citestcycle-intake";
  static ObjectMapper msgPackMapper = new ObjectMapper(new MessagePackFactory());

  // Track last request state
  volatile byte[] lastRequestBody;
  volatile String lastRequestContentType;
  volatile Map<String, String> lastRequestHeaders;
  volatile String lastRequestPath;

  HttpServer activeServer;

  @AfterEach
  void tearDown() throws Exception {
    if (activeServer != null) {
      activeServer.stop(0);
      activeServer = null;
    }
  }

  HttpServer newAgentEvpProxy(String path) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        path,
        exchange -> {
          captureRequest(exchange);
          if (!"application/msgpack".equals(lastRequestContentType)) {
            byte[] msg = ("wrong type: " + lastRequestContentType).getBytes();
            exchange.sendResponseHeaders(400, msg.length);
            exchange.getResponseBody().write(msg);
            exchange.getResponseBody().close();
          } else {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
          }
        });
    server.start();
    return server;
  }

  void captureRequest(HttpExchange exchange) throws IOException {
    lastRequestBody = readAllBytes(exchange);
    lastRequestContentType = exchange.getRequestHeaders().getFirst("Content-Type");
    lastRequestPath = exchange.getRequestURI().getPath();
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    exchange.getRequestHeaders().forEach((k, v) -> headers.put(k, v.get(0)));
    lastRequestHeaders = headers;
  }

  @Test
  void sendingAnEmptyListOfTracesReturnsNoErrors() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    String evpProxyEndpoint = V2_EVP_PROXY_ENDPOINT;
    String path = buildAgentEvpProxyPath(evpProxyEndpoint, trackType, apiVersion);
    HttpServer agentEvpProxy = newAgentEvpProxy(path);
    activeServer = agentEvpProxy;

    DDEvpProxyApi client =
        createEvpProxyApi(
            "http://localhost:" + agentEvpProxy.getAddress().getPort(),
            evpProxyEndpoint,
            trackType,
            false);
    Payload payload = prepareTraces(trackType, false, Collections.<List<DDSpan>>emptyList());

    datadog.trace.common.writer.RemoteApi.Response clientResponse =
        client.sendSerializedTraces(payload);
    assertTrue(clientResponse.success());
    assertTrue(clientResponse.status().isPresent());
    assertEquals(200, clientResponse.status().getAsInt());
    assertEquals(path, lastRequestPath);
    assertEquals(intakeSubdomain, lastRequestHeaders.get("X-Datadog-EVP-Subdomain"));
  }

  @Test
  void retriesWhenBackendReturns5xx() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    String evpProxyEndpoint = V2_EVP_PROXY_ENDPOINT;
    String path = buildAgentEvpProxyPath(evpProxyEndpoint, trackType, apiVersion);

    AtomicInteger retryCount = new AtomicInteger(1);
    HttpServer agentEvpProxy = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = agentEvpProxy;
    agentEvpProxy.createContext(
        path,
        exchange -> {
          captureRequest(exchange);
          if (retryCount.get() < 5) {
            exchange.sendResponseHeaders(503, 0);
            retryCount.incrementAndGet();
          } else {
            exchange.sendResponseHeaders(200, 0);
          }
          exchange.getResponseBody().close();
        });
    agentEvpProxy.start();

    DDEvpProxyApi client =
        createEvpProxyApi(
            "http://localhost:" + agentEvpProxy.getAddress().getPort(),
            evpProxyEndpoint,
            trackType,
            false);
    Payload payload = prepareTraces(trackType, false, Collections.<List<DDSpan>>emptyList());

    datadog.trace.common.writer.RemoteApi.Response clientResponse =
        client.sendSerializedTraces(payload);
    assertTrue(clientResponse.success());
    assertTrue(clientResponse.status().isPresent());
    assertEquals(200, clientResponse.status().getAsInt());
    assertEquals(path, lastRequestPath);
    assertEquals(intakeSubdomain, lastRequestHeaders.get("X-Datadog-EVP-Subdomain"));
  }

  @Test
  void contentIsSentAsMsgpackEmpty() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    String evpProxyEndpoint = V2_EVP_PROXY_ENDPOINT;
    boolean compressionEnabled = false;

    String path = buildAgentEvpProxyPath(evpProxyEndpoint, trackType, apiVersion);
    HttpServer agentEvpProxy = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = agentEvpProxy;
    agentEvpProxy.createContext(
        path,
        exchange -> {
          captureRequest(exchange);
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
        });
    agentEvpProxy.start();

    DDEvpProxyApi client =
        createEvpProxyApi(
            "http://localhost:" + agentEvpProxy.getAddress().getPort(),
            evpProxyEndpoint,
            trackType,
            compressionEnabled);
    Payload payload =
        prepareTraces(trackType, compressionEnabled, Collections.<List<DDSpan>>emptyList());

    client.sendSerializedTraces(payload);
    assertEquals("application/msgpack", lastRequestContentType);
    assertTrue(convertMap(lastRequestBody, compressionEnabled).isEmpty());
  }

  @Test
  void contentIsSentAsMsgpackWithSpan() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    String evpProxyEndpoint = V2_EVP_PROXY_ENDPOINT;
    boolean compressionEnabled = false;

    String path = buildAgentEvpProxyPath(evpProxyEndpoint, trackType, apiVersion);
    HttpServer agentEvpProxy = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = agentEvpProxy;
    agentEvpProxy.createContext(
        path,
        exchange -> {
          captureRequest(exchange);
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
        });
    agentEvpProxy.start();

    DDEvpProxyApi client =
        createEvpProxyApi(
            "http://localhost:" + agentEvpProxy.getAddress().getPort(),
            evpProxyEndpoint,
            trackType,
            compressionEnabled);

    Map<String, Object> serviceTags = new HashMap<>();
    serviceTags.put("service.name", "my-service");
    DDSpan span = buildSpan(1L, "fakeType", serviceTags);
    span.finish();
    setDurationNano(span, 10);
    List<DDSpan> trace = Collections.singletonList(span);
    Payload payload =
        prepareTraces(trackType, compressionEnabled, Collections.singletonList(trace));

    client.sendSerializedTraces(payload);
    assertEquals("application/msgpack", lastRequestContentType);

    Map<String, Object> body = convertMap(lastRequestBody, compressionEnabled);
    assertEquals(1, body.get("version"));
    assertNotNull(body.get("metadata"));
    assertNotNull(body.get("events"));
  }

  @Test
  void contentIsSentAsMsgpackCompressed() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    String evpProxyEndpoint = V4_EVP_PROXY_ENDPOINT;
    boolean compressionEnabled = true;

    String path = buildAgentEvpProxyPath(evpProxyEndpoint, trackType, apiVersion);
    HttpServer agentEvpProxy = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = agentEvpProxy;
    agentEvpProxy.createContext(
        path,
        exchange -> {
          captureRequest(exchange);
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
        });
    agentEvpProxy.start();

    DDEvpProxyApi client =
        createEvpProxyApi(
            "http://localhost:" + agentEvpProxy.getAddress().getPort(),
            evpProxyEndpoint,
            trackType,
            compressionEnabled);

    Map<String, Object> moduleTags = new HashMap<>();
    moduleTags.put("test_module_id", 456L);
    DDSpan span = buildSpan(1L, InternalSpanTypes.TEST_MODULE_END, moduleTags);
    span.finish();
    setDurationNano(span, 10);
    List<DDSpan> trace = Collections.singletonList(span);
    Payload payload =
        prepareTraces(trackType, compressionEnabled, Collections.singletonList(trace));

    client.sendSerializedTraces(payload);
    assertEquals("application/msgpack", lastRequestContentType);

    Map<String, Object> body = convertMap(lastRequestBody, compressionEnabled);
    assertEquals(1, body.get("version"));
  }

  static Map<String, Object> convertMap(byte[] bytes, boolean compressionEnabled) throws Exception {
    if (compressionEnabled) {
      bytes = decompress(bytes);
    }
    if (bytes == null || bytes.length == 0) {
      return Collections.emptyMap();
    }
    return msgPackMapper.readValue(bytes, new TypeReference<TreeMap<String, Object>>() {});
  }

  static byte[] decompress(byte[] bytes) throws IOException {
    ByteArrayOutputStream baos = new ByteArrayOutputStream();
    try (GZIPInputStream zip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
      IOUtils.copy(zip, baos);
    }
    return baos.toByteArray();
  }

  static class Traces implements ByteBufferConsumer {
    int traceCount;
    ByteBuffer buffer;

    @Override
    public void accept(int messageCount, ByteBuffer buffer) {
      this.buffer = buffer;
      this.traceCount = messageCount;
    }
  }

  DDEvpProxyApi createEvpProxyApi(
      String agentUrl, String evpProxyEndpoint, TrackType trackType, boolean compressionEnabled) {
    return DDEvpProxyApi.builder()
        .agentUrl(HttpUrl.get(agentUrl))
        .evpProxyEndpoint(evpProxyEndpoint)
        .trackType(trackType)
        .compressionEnabled(compressionEnabled)
        .build();
  }

  datadog.trace.common.writer.RemoteMapper discoverMapper(
      TrackType trackType, boolean compressionEnabled) {
    DDIntakeMapperDiscovery mapperDiscovery =
        new DDIntakeMapperDiscovery(trackType, wellKnownTags, compressionEnabled);
    mapperDiscovery.discover();
    return mapperDiscovery.getMapper();
  }

  String buildAgentEvpProxyPath(String evpProxyEndpoint, TrackType trackType, String apiVersion) {
    return "/" + evpProxyEndpoint + "api/" + apiVersion + "/" + trackType.name().toLowerCase();
  }

  Payload prepareTraces(TrackType trackType, boolean compressionEnabled, List<List<DDSpan>> traces)
      throws Exception {
    Traces traceCapture = new Traces();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1 << 20, traceCapture));
    datadog.trace.common.writer.RemoteMapper mapper = discoverMapper(trackType, compressionEnabled);
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

  static byte[] readAllBytes(HttpExchange exchange) throws IOException {
    try (InputStream is = exchange.getRequestBody();
        ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
      byte[] buf = new byte[4096];
      int n;
      while ((n = is.read(buf)) != -1) {
        baos.write(buf, 0, n);
      }
      return baos.toByteArray();
    }
  }

  static void assertNotNull(Object obj) {
    org.junit.jupiter.api.Assertions.assertNotNull(obj);
  }

  void setDurationNano(DDSpan span, long duration) throws Exception {
    java.lang.reflect.Field f = DDSpan.class.getDeclaredField("durationNano");
    f.setAccessible(true);
    f.set(span, duration);
  }
}
