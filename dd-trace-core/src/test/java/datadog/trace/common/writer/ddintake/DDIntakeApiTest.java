package datadog.trace.common.writer.ddintake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.common.writer.RemoteMapper;
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
class DDIntakeApiTest extends DDCoreSpecification {

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

  static String apiKey = "my-secret-apikey";
  static ObjectMapper msgPackMapper = new ObjectMapper(new MessagePackFactory());

  volatile byte[] lastRequestBody;
  volatile String lastRequestContentType;
  volatile String lastRequestPath;
  volatile Map<String, String> lastRequestHeaders;

  HttpServer activeServer;

  @AfterEach
  void tearDown() throws Exception {
    if (activeServer != null) {
      activeServer.stop(0);
      activeServer = null;
    }
  }

  HttpServer newIntake(String path) throws Exception {
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
    lastRequestHeaders = new HashMap<>();
    exchange.getRequestHeaders().forEach((k, v) -> lastRequestHeaders.put(k, v.get(0)));
  }

  @Test
  void sendingAnEmptyListOfTracesReturnsNoErrors() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    String path = buildIntakePath(trackType, apiVersion);
    HttpServer intake = newIntake(path);
    activeServer = intake;

    DDIntakeApi client =
        createIntakeApi("http://localhost:" + intake.getAddress().getPort(), trackType);
    Payload payload = prepareTraces(trackType, Collections.<List<DDSpan>>emptyList());

    RemoteApi.Response clientResponse = client.sendSerializedTraces(payload);
    assertTrue(clientResponse.success());
    assertTrue(clientResponse.status().isPresent());
    assertEquals(200, clientResponse.status().getAsInt());
    assertEquals(path, lastRequestPath);
  }

  @Test
  void retriesWhenBackendReturns5xx() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    String path = buildIntakePath(trackType, apiVersion);

    AtomicInteger retryCount = new AtomicInteger(1);
    HttpServer intake = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = intake;
    intake.createContext(
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
    intake.start();

    DDIntakeApi client =
        createIntakeApi("http://localhost:" + intake.getAddress().getPort(), trackType);
    Payload payload = prepareTraces(trackType, Collections.<List<DDSpan>>emptyList());

    RemoteApi.Response clientResponse = client.sendSerializedTraces(payload);
    assertTrue(clientResponse.success());
    assertTrue(clientResponse.status().isPresent());
    assertEquals(200, clientResponse.status().getAsInt());
    assertEquals(path, lastRequestPath);
  }

  @Test
  void retriesWhenBackendReturns429TooManyRequests() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    String path = buildIntakePath(trackType, apiVersion);

    AtomicInteger retryCount = new AtomicInteger(0);
    HttpServer intake = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = intake;
    intake.createContext(
        path,
        exchange -> {
          captureRequest(exchange);
          if (retryCount.get() < 1) {
            exchange.getResponseHeaders().add("x-ratelimit-reset", "0");
            exchange.sendResponseHeaders(429, 0);
            retryCount.incrementAndGet();
          } else {
            exchange.sendResponseHeaders(200, 0);
          }
          exchange.getResponseBody().close();
        });
    intake.start();

    DDIntakeApi client =
        createIntakeApi("http://localhost:" + intake.getAddress().getPort(), trackType);
    Payload payload = prepareTraces(trackType, Collections.<List<DDSpan>>emptyList());

    RemoteApi.Response clientResponse = client.sendSerializedTraces(payload);
    assertTrue(clientResponse.success());
    assertTrue(clientResponse.status().isPresent());
    assertEquals(200, clientResponse.status().getAsInt());
    assertEquals(path, lastRequestPath);
  }

  @Test
  void contentIsSentAsMsgpackEmpty() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    String path = buildIntakePath(trackType, apiVersion);
    HttpServer intake = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = intake;
    intake.createContext(
        path,
        exchange -> {
          captureRequest(exchange);
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
        });
    intake.start();

    DDIntakeApi client =
        createIntakeApi("http://localhost:" + intake.getAddress().getPort(), trackType);
    Payload payload = prepareTraces(trackType, Collections.<List<DDSpan>>emptyList());

    assertTrue(client.sendSerializedTraces(payload).status().isPresent());
    assertEquals("application/msgpack", lastRequestContentType);
    assertTrue(convertMap(lastRequestBody).isEmpty());
  }

  @Test
  void contentIsSentAsMsgpackWithSpan() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    String path = buildIntakePath(trackType, apiVersion);
    HttpServer intake = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = intake;
    intake.createContext(
        path,
        exchange -> {
          captureRequest(exchange);
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
        });
    intake.start();

    DDIntakeApi client =
        createIntakeApi("http://localhost:" + intake.getAddress().getPort(), trackType);

    Map<String, Object> serviceTags = new HashMap<>();
    serviceTags.put("service.name", "my-service");
    DDSpan span = buildSpan(1L, "fakeType", serviceTags);
    span.finish();
    setDurationNano(span, 10);

    Payload payload =
        prepareTraces(trackType, Collections.singletonList(Collections.singletonList(span)));

    assertTrue(client.sendSerializedTraces(payload).status().isPresent());
    assertEquals("application/msgpack", lastRequestContentType);

    Map<String, Object> body = convertMap(lastRequestBody);
    assertEquals(1, body.get("version"));
    assertNotNull(body.get("metadata"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");
    assertNotNull(events);
    assertEquals(1, events.size());

    @SuppressWarnings("unchecked")
    Map<String, Object> event = events.get(0);
    assertEquals("span", event.get("type"));
    assertEquals(1, event.get("version"));

    @SuppressWarnings("unchecked")
    Map<String, Object> content = (Map<String, Object>) event.get("content");
    assertEquals("my-service", content.get("service"));
    assertEquals("fakeOperation", content.get("name"));
    assertEquals("fakeResource", content.get("resource"));
  }

  @Test
  void contentIsSentAsMsgpackWithTestSpan() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    String path = buildIntakePath(trackType, apiVersion);
    HttpServer intake = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = intake;
    intake.createContext(
        path,
        exchange -> {
          captureRequest(exchange);
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
        });
    intake.start();

    DDIntakeApi client =
        createIntakeApi("http://localhost:" + intake.getAddress().getPort(), trackType);

    Map<String, Object> testTags = new HashMap<>();
    testTags.put("test_suite_id", 123L);
    testTags.put("test_module_id", 456L);
    DDSpan span = buildSpan(1L, InternalSpanTypes.TEST, testTags);
    span.finish();
    setDurationNano(span, 10);

    Payload payload =
        prepareTraces(trackType, Collections.singletonList(Collections.singletonList(span)));

    assertTrue(client.sendSerializedTraces(payload).status().isPresent());
    assertEquals("application/msgpack", lastRequestContentType);

    Map<String, Object> body = convertMap(lastRequestBody);
    assertEquals(1, body.get("version"));

    @SuppressWarnings("unchecked")
    List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");
    assertEquals(1, events.size());
    assertEquals("test", events.get(0).get("type"));
    assertEquals(2, events.get(0).get("version"));
  }

  @Test
  void contentIsSentAsMsgpackWithTestSuiteEnd() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    String path = buildIntakePath(trackType, apiVersion);
    HttpServer intake = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = intake;
    intake.createContext(
        path,
        exchange -> {
          captureRequest(exchange);
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
        });
    intake.start();

    DDIntakeApi client =
        createIntakeApi("http://localhost:" + intake.getAddress().getPort(), trackType);

    Map<String, Object> suiteTags = new HashMap<>();
    suiteTags.put("test_suite_id", 123L);
    suiteTags.put("test_module_id", 456L);
    DDSpan span = buildSpan(1L, InternalSpanTypes.TEST_SUITE_END, suiteTags);
    span.finish();
    setDurationNano(span, 10);

    Payload payload =
        prepareTraces(trackType, Collections.singletonList(Collections.singletonList(span)));

    assertTrue(client.sendSerializedTraces(payload).status().isPresent());
    assertEquals("application/msgpack", lastRequestContentType);

    Map<String, Object> body = convertMap(lastRequestBody);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");
    assertEquals(1, events.size());
    assertEquals("test_suite_end", events.get(0).get("type"));
  }

  @Test
  void contentIsSentAsMsgpackWithTestModuleEnd() throws Exception {
    TrackType trackType = TrackType.CITESTCYCLE;
    String apiVersion = "v2";
    String path = buildIntakePath(trackType, apiVersion);
    HttpServer intake = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = intake;
    intake.createContext(
        path,
        exchange -> {
          captureRequest(exchange);
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
        });
    intake.start();

    DDIntakeApi client =
        createIntakeApi("http://localhost:" + intake.getAddress().getPort(), trackType);

    Map<String, Object> moduleTags = new HashMap<>();
    moduleTags.put("test_module_id", 456L);
    DDSpan span = buildSpan(1L, InternalSpanTypes.TEST_MODULE_END, moduleTags);
    span.finish();
    setDurationNano(span, 10);

    Payload payload =
        prepareTraces(trackType, Collections.singletonList(Collections.singletonList(span)));

    assertTrue(client.sendSerializedTraces(payload).status().isPresent());
    assertEquals("application/msgpack", lastRequestContentType);

    Map<String, Object> body = convertMap(lastRequestBody);
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> events = (List<Map<String, Object>>) body.get("events");
    assertEquals(1, events.size());
    assertEquals("test_module_end", events.get(0).get("type"));
  }

  static Map<String, Object> convertMap(byte[] bytes) throws Exception {
    byte[] decompressed = decompress(bytes);
    if (decompressed == null || decompressed.length == 0) {
      return Collections.emptyMap();
    }
    return msgPackMapper.readValue(decompressed, new TypeReference<TreeMap<String, Object>>() {});
  }

  static byte[] decompress(byte[] bytes) throws IOException {
    if (bytes == null || bytes.length == 0) {
      return bytes;
    }
    try {
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      try (GZIPInputStream zip = new GZIPInputStream(new ByteArrayInputStream(bytes))) {
        IOUtils.copy(zip, baos);
      }
      return baos.toByteArray();
    } catch (IOException e) {
      // May not be gzip-compressed
      return bytes;
    }
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

  DDIntakeApi createIntakeApi(String url, TrackType trackType) {
    HttpUrl hostUrl = HttpUrl.get(url);
    return DDIntakeApi.builder().hostUrl(hostUrl).trackType(trackType).apiKey(apiKey).build();
  }

  RemoteMapper discoverMapper(TrackType trackType) {
    DDIntakeMapperDiscovery mapperDiscovery =
        new DDIntakeMapperDiscovery(trackType, wellKnownTags, true);
    mapperDiscovery.discover();
    return mapperDiscovery.getMapper();
  }

  String buildIntakePath(TrackType trackType, String apiVersion) {
    return String.format("/api/%s/%s", apiVersion, trackType.name().toLowerCase());
  }

  Payload prepareTraces(TrackType trackType, List<List<DDSpan>> traces) throws Exception {
    Traces traceCapture = new Traces();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1 << 20, traceCapture));
    RemoteMapper mapper = discoverMapper(trackType);
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

  void setDurationNano(DDSpan span, long duration) throws Exception {
    java.lang.reflect.Field f = DDSpan.class.getDeclaredField("durationNano");
    f.setAccessible(true);
    f.set(span, duration);
  }
}
