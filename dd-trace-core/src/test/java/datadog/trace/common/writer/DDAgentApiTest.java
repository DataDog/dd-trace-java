package datadog.trace.common.writer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.metrics.api.Monitoring;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.TraceMapperV0_4;
import datadog.trace.common.writer.ddagent.TraceMapperV0_5;
import datadog.trace.core.DDSpan;
import datadog.trace.core.test.DDCoreSpecification;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.msgpack.jackson.dataformat.MessagePackFactory;

@Timeout(20)
class DDAgentApiTest extends DDCoreSpecification {

  Monitoring monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
  static ObjectMapper mapper = new ObjectMapper(new MessagePackFactory());

  // Track the last request for assertions
  static volatile byte[] lastRequestBody;
  static volatile String lastRequestContentType;
  static volatile Map<String, String> lastRequestHeaders;
  static volatile String lastRequestPath;

  HttpServer activeServer;

  @AfterEach
  void tearDownServer() throws Exception {
    if (activeServer != null) {
      activeServer.stop(0);
      activeServer = null;
    }
  }

  static HttpServer newAgent(String latestVersion) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/" + latestVersion,
        exchange -> {
          lastRequestBody = readBody(exchange);
          lastRequestContentType = exchange.getRequestHeaders().getFirst("Content-Type");
          lastRequestPath = exchange.getRequestURI().getPath();
          Map<String, String> reqHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
          exchange.getRequestHeaders().forEach((k, v) -> reqHeaders.put(k, v.get(0)));
          lastRequestHeaders = reqHeaders;
          if (!"application/msgpack"
              .equals(exchange.getRequestHeaders().getFirst("Content-Type"))) {
            byte[] msg =
                ("wrong type: " + exchange.getRequestHeaders().getFirst("Content-Type")).getBytes();
            exchange.sendResponseHeaders(400, msg.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(msg);
            }
          } else if (lastRequestBody.length == 0) {
            byte[] msg = "no content".getBytes();
            exchange.sendResponseHeaders(400, msg.length);
            try (OutputStream os = exchange.getResponseBody()) {
              os.write(msg);
            }
          } else {
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().close();
          }
        });
    server.start();
    return server;
  }

  static byte[] readBody(HttpExchange exchange) throws IOException {
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

  @ParameterizedTest
  @MethodSource("agentVersionsArgs")
  void sendingAnEmptyListOfTracesReturnsNoErrors(String agentVersion) throws Exception {
    HttpServer agent = newAgent(agentVersion);
    activeServer = agent;
    String url = "http://localhost:" + agent.getAddress().getPort();
    DDAgentApi client = (DDAgentApi) createAgentApi(url)[1];
    Payload payload = prepareTraces(agentVersion, Collections.emptyList());

    RemoteApi.Response response = client.sendSerializedTraces(payload);
    assertTrue(response.success());
    assertTrue(response.status().isPresent());
    assertEquals(200, response.status().getAsInt());
    assertEquals("/" + agentVersion, lastRequestPath);
  }

  static Stream<Arguments> agentVersionsArgs() {
    return Stream.of(
        Arguments.of("v0.3/traces"), Arguments.of("v0.4/traces"), Arguments.of("v0.5/traces"));
  }

  @Test
  void responseBodyPropagatedInCaseOfNon200Response() throws Exception {
    HttpServer agent = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = agent;
    agent.createContext(
        "/v0.4/traces",
        exchange -> {
          lastRequestBody = readBody(exchange);
          lastRequestPath = exchange.getRequestURI().getPath();
          byte[] msg = "Test error".getBytes();
          exchange.sendResponseHeaders(400, msg.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(msg);
          }
        });
    agent.start();

    String url = "http://localhost:" + agent.getAddress().getPort();
    DDAgentApi client = (DDAgentApi) createAgentApi(url)[1];
    Payload payload = prepareTraces("v0.4/traces", Collections.emptyList());

    RemoteApi.Response clientResponse = client.sendSerializedTraces(payload);
    assertFalse(clientResponse.success());
    assertTrue(clientResponse.status().isPresent());
    assertEquals(400, clientResponse.status().getAsInt());
    assertEquals("Test error", clientResponse.response());
  }

  @Test
  void non200Response() throws Exception {
    HttpServer agent = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = agent;
    agent.createContext(
        "/v0.4/traces",
        exchange -> {
          readBody(exchange);
          lastRequestPath = exchange.getRequestURI().getPath();
          exchange.sendResponseHeaders(404, 0);
          exchange.getResponseBody().close();
        });
    agent.createContext(
        "/v0.3/traces",
        exchange -> {
          readBody(exchange);
          lastRequestPath = exchange.getRequestURI().getPath();
          exchange.sendResponseHeaders(404, 0);
          exchange.getResponseBody().close();
        });
    agent.start();

    String url = "http://localhost:" + agent.getAddress().getPort();
    DDAgentApi client = (DDAgentApi) createAgentApi(url)[1];
    Payload payload = prepareTraces("v0.3/traces", Collections.emptyList());

    RemoteApi.Response clientResponse = client.sendSerializedTraces(payload);
    assertFalse(clientResponse.success());
    assertTrue(clientResponse.status().isPresent());
    assertEquals(404, clientResponse.status().getAsInt());
    assertEquals("/v0.3/traces", lastRequestPath);
  }

  static Stream<Arguments> contentSentAsMsgpackArgs() {
    return Stream.of(
        Arguments.of("v0.3/traces", Collections.emptyList(), Collections.emptyList()),
        Arguments.of("v0.4/traces", Collections.emptyList(), Collections.emptyList()),
        Arguments.of("v0.4/traces", Collections.emptyList(), Collections.emptyList()));
  }

  @Test
  void embeddedHttpClientRejectsAsyncRequests() throws Exception {
    HttpServer agent = newAgent("v0.5/traces");
    activeServer = agent;
    String url = "http://localhost:" + agent.getAddress().getPort();
    Object[] apiTuple = createAgentApi(url);
    DDAgentFeaturesDiscovery discovery = (DDAgentFeaturesDiscovery) apiTuple[0];
    DDAgentApi client = (DDAgentApi) apiTuple[1];
    discovery.discover();
    java.lang.reflect.Field httpClientField = DDAgentApi.class.getDeclaredField("httpClient");
    httpClientField.setAccessible(true);
    OkHttpClient httpClient = (OkHttpClient) httpClientField.get(client);
    java.util.concurrent.ExecutorService httpExecutorService =
        httpClient.dispatcher().executorService();
    assertThrows(RejectedExecutionException.class, () -> httpExecutorService.execute(() -> {}));
    assertTrue(httpExecutorService.isShutdown());
  }

  @Test
  void verifyContentLength() throws Exception {
    AtomicLong receivedContentLength = new AtomicLong();
    String agentVersion = "v0.4/traces";
    HttpServer agent = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = agent;
    agent.createContext(
        "/" + agentVersion,
        exchange -> {
          lastRequestBody = readBody(exchange);
          Map<String, String> verifyHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
          exchange.getRequestHeaders().forEach((k, v) -> verifyHeaders.put(k, v.get(0)));
          lastRequestHeaders = verifyHeaders;
          receivedContentLength.set(lastRequestBody.length);
          exchange.sendResponseHeaders(200, 0);
          exchange.getResponseBody().close();
        });
    agent.start();

    String url = "http://localhost:" + agent.getAddress().getPort();
    DDAgentApi client = (DDAgentApi) createAgentApi(url)[1];
    List<List<DDSpan>> traces = Collections.emptyList();
    Payload payload = prepareTraces(agentVersion, traces);

    boolean success = client.sendSerializedTraces(payload).success();
    assertTrue(success);
    assertEquals(1, receivedContentLength.get());
  }

  @Test
  void apiResponseListenersSee200Responses() throws Exception {
    AtomicReference<Map> agentResponse = new AtomicReference<>(null);
    RemoteResponseListener responseListener =
        (endpoint, responseJson) -> agentResponse.set(responseJson);

    String agentVersion = "v0.4/traces";
    HttpServer agent = HttpServer.create(new InetSocketAddress(0), 0);
    activeServer = agent;
    agent.createContext(
        "/" + agentVersion,
        exchange -> {
          lastRequestBody = readBody(exchange);
          Map<String, String> listenersHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
          exchange.getRequestHeaders().forEach((k, v) -> listenersHeaders.put(k, v.get(0)));
          lastRequestHeaders = listenersHeaders;
          lastRequestPath = exchange.getRequestURI().getPath();
          int status = lastRequestBody.length > 0 ? 200 : 500;
          byte[] responseBody = "{\"hello\":{}}".getBytes();
          exchange.sendResponseHeaders(status, responseBody.length);
          try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBody);
          }
        });
    agent.start();

    String url = "http://localhost:" + agent.getAddress().getPort();
    DDAgentApi client = (DDAgentApi) createAgentApi(url)[1];
    client.addResponseListener(responseListener);
    List<List<DDSpan>> emptyTraces =
        Arrays.asList(
            Collections.<DDSpan>emptyList(),
            Collections.<DDSpan>emptyList(),
            Collections.<DDSpan>emptyList());
    Payload payload = prepareTraces(agentVersion, emptyTraces);
    payload.withDroppedTraces(1);
    payload.withDroppedTraces(3);

    client.sendSerializedTraces(payload);

    Map expected = new HashMap();
    expected.put("hello", Collections.emptyMap());
    assertEquals(expected, agentResponse.get());
    assertEquals("java", lastRequestHeaders.get("Datadog-Meta-Lang"));
    assertEquals(
        System.getProperty("java.version", "unknown"),
        lastRequestHeaders.get("Datadog-Meta-Lang-Version"));
    assertEquals("Stubbed-Test-Version", lastRequestHeaders.get("Datadog-Meta-Tracer-Version"));
    assertEquals("3", lastRequestHeaders.get("X-Datadog-Trace-Count"));
  }

  static List<List<TreeMap<String, Object>>> convertList(String agentVersion, byte[] bytes)
      throws Exception {
    if ("v0.5/traces".equals(agentVersion)) {
      return convertListV5(bytes);
    }
    List<List<TreeMap<String, Object>>> returnVal =
        mapper.readValue(bytes, new TypeReference<List<List<TreeMap<String, Object>>>>() {});
    for (List<TreeMap<String, Object>> trace : returnVal) {
      for (TreeMap<String, Object> span : trace) {
        ((Map) span.get("meta")).remove("runtime-id");
        ((Map) span.get("meta")).remove("language");
      }
    }
    return returnVal;
  }

  static List<List<TreeMap<String, Object>>> convertListV5(byte[] bytes) throws Exception {
    List<List<List<Object>>> traces =
        mapper.readValue(bytes, new TypeReference<List<List<List<Object>>>>() {});
    List<List<TreeMap<String, Object>>> maps = new ArrayList<>(traces.size());
    for (List<List<Object>> trace : traces) {
      List<TreeMap<String, Object>> mapTrace = new ArrayList<>();
      for (List<Object> span : trace) {
        TreeMap<String, Object> map = new TreeMap<>();
        if (!span.isEmpty()) {
          map.put("service", span.get(0));
          map.put("name", span.get(1));
          map.put("resource", span.get(2));
          map.put("trace_id", span.get(3));
          map.put("span_id", span.get(4));
          map.put("parent_id", span.get(5));
          map.put("start", span.get(6));
          map.put("duration", span.get(7));
          map.put("error", span.get(8));
          map.put("meta", span.get(9));
          map.put("metrics", span.get(10));
          map.put("type", span.get(11));
          ((Map) map.get("meta")).remove("runtime-id");
          ((Map) map.get("meta")).remove("language");
        }
        mapTrace.add(map);
      }
      maps.add(mapTrace);
    }
    return maps;
  }

  Payload prepareTraces(String agentVersion, List<List<DDSpan>> traces) throws Exception {
    Traces traceCapture = new Traces();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1 << 20, traceCapture));
    RemoteMapper traceMapper =
        "v0.5/traces".equals(agentVersion) ? new TraceMapperV0_5() : new TraceMapperV0_4();
    for (List<DDSpan> trace : traces) {
      packer.format(trace, traceMapper);
    }
    packer.flush();
    return traceMapper
        .newPayload()
        .withBody(
            traceCapture.traceCount,
            traces.isEmpty() ? ByteBuffer.allocate(0) : traceCapture.buffer);
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

  Object[] createAgentApi(String url) {
    HttpUrl agentUrl = HttpUrl.get(url);
    OkHttpClient client = OkHttpUtils.buildHttpClient(agentUrl, 1000);
    DDAgentFeaturesDiscovery discovery =
        new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, true, true);
    return new Object[] {discovery, new DDAgentApi(client, agentUrl, discovery, monitoring, false)};
  }
}
