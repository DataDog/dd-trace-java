package datadog.trace.common.writer;

import static datadog.trace.api.ProtocolVersion.V0_5;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.metrics.api.Monitoring;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.api.Config;
import datadog.trace.api.ProcessTags;
import datadog.trace.api.ProtocolVersion;
import datadog.trace.bootstrap.instrumentation.api.InstrumentationTags;
import datadog.trace.common.sampling.RateByServiceTraceSampler;
import datadog.trace.common.writer.RemoteApi.Response;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.TraceMapper;
import datadog.trace.common.writer.ddagent.TraceMapperV0_4;
import datadog.trace.common.writer.ddagent.TraceMapperV0_5;
import datadog.trace.common.writer.ddagent.TraceMapperV1;
import datadog.trace.core.DDCoreJavaSpecification;
import datadog.trace.core.DDSpan;
import datadog.trace.core.DDSpanContext;
import datadog.trace.core.propagation.PropagationTags;
import datadog.trace.junit.utils.tabletest.TableTestTypeConverters;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.msgpack.jackson.dataformat.MessagePackFactory;
import org.tabletest.junit.TableTest;
import org.tabletest.junit.TypeConverterSources;

@Timeout(20)
@TypeConverterSources(TableTestTypeConverters.class)
public class DDAgentApiTest extends DDCoreJavaSpecification {

  static final Monitoring monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
  static final ObjectMapper mapper = new ObjectMapper(new MessagePackFactory());

  // --- Helper: create a minimal agent server responding 200 to PUT latestVersion ---

  static JavaTestHttpServer newAgent(String latestVersion) {
    return JavaTestHttpServer.httpServer(
        s ->
            s.handlers(
                h ->
                    h.put(
                        latestVersion,
                        api -> {
                          if (!"application/msgpack".equals(api.getRequest().getContentType())) {
                            api.getResponse()
                                .status(400)
                                .send("wrong type: " + api.getRequest().getContentType());
                          } else if (api.getRequest().getContentLength() <= 0) {
                            api.getResponse().status(400).send("no content");
                          } else {
                            api.getResponse().status(200).send();
                          }
                        })));
  }

  // --- Tests ---

  @TableTest({
    "scenario    | agentVersion  | protocolVersion",
    "v0.3 traces | 'v0.3/traces' | V0_4           ",
    "v0.4 traces | 'v0.4/traces' | V0_4           ",
    "v0.5 traces | 'v0.5/traces' | V0_5           ",
    "v1.0 traces | 'v1.0/traces' | V1_0           "
  })
  void testSendingAnEmptyListOfTracesReturnsNoErrors(
      String agentVersion, ProtocolVersion protocolVersion) {
    JavaTestHttpServer agent = newAgent(agentVersion);
    try {
      DDAgentApi client = createAgentApi(agent.getAddress().toString(), protocolVersion).api;
      Payload payload = prepareTraces(agentVersion, emptyList());
      Response response = client.sendSerializedTraces(payload);
      assertTrue(response.success());
      assertTrue(response.status().isPresent());
      assertEquals(200, response.status().getAsInt());
      assertEquals("/" + agentVersion, agent.getLastRequest().getPath());
    } finally {
      agent.close();
    }
  }

  @Test
  void testResponseBodyPropagatedInCaseOfNon200Response() {
    JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.put(
                            "v0.4/traces",
                            api -> api.getResponse().status(400).send("Test error"))));
    try {
      DDAgentApi client = createAgentApi(agent.getAddress().toString()).api;
      Payload payload = prepareTraces("v0.4/traces", emptyList());
      Response clientResponse = client.sendSerializedTraces(payload);
      assertFalse(clientResponse.success());
      assertTrue(clientResponse.status().isPresent());
      assertEquals(400, clientResponse.status().getAsInt());
      assertEquals("Test error", clientResponse.response());
    } finally {
      agent.close();
    }
  }

  @Test
  void testNon200Response() {
    JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h -> {
                      h.put("v0.4/traces", api -> api.getResponse().status(404).send());
                      h.put("v0.3/traces", api -> api.getResponse().status(404).send());
                    }));
    try {
      DDAgentApi client = createAgentApi(agent.getAddress().toString()).api;
      Payload payload = prepareTraces("v0.3/traces", emptyList());
      Response clientResponse = client.sendSerializedTraces(payload);
      assertFalse(clientResponse.success());
      assertTrue(clientResponse.status().isPresent());
      assertEquals(404, clientResponse.status().getAsInt());
      assertEquals("/v0.3/traces", agent.getLastRequest().getPath());
    } finally {
      agent.close();
    }
  }

  @Test
  void testContentIsSentAsMsgpackEmptyTraces() throws IOException {
    String agentVersion = "v0.3/traces";
    List<List<DDSpan>> traces = emptyList();
    JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            s -> s.handlers(h -> h.put(agentVersion, api -> api.getResponse().send())));
    try {
      DDAgentApi client = createAgentApi(agent.getAddress().toString()).api;
      Payload payload = prepareTraces(agentVersion, traces);
      assertTrue(client.sendSerializedTraces(payload).success());
      assertEquals("application/msgpack", agent.getLastRequest().getContentType());
      assertEquals(
          "true", agent.getLastRequest().getHeaders().get("Datadog-Client-Computed-Top-Level"));
      assertEquals("java", agent.getLastRequest().getHeaders().get("Datadog-Meta-Lang"));
      assertEquals(
          System.getProperty("java.version", "unknown"),
          agent.getLastRequest().getHeaders().get("Datadog-Meta-Lang-Version"));
      assertEquals(
          "Stubbed-Test-Version",
          agent.getLastRequest().getHeaders().get("Datadog-Meta-Tracer-Version"));
      assertEquals(
          String.valueOf(traces.size()),
          agent.getLastRequest().getHeaders().get("X-Datadog-Trace-Count"));
      assertEquals(
          String.valueOf(payload.droppedTraces()),
          agent.getLastRequest().getHeaders().get("Datadog-Client-Dropped-P0-Traces"));
      assertEquals(
          String.valueOf(payload.droppedSpans()),
          agent.getLastRequest().getHeaders().get("Datadog-Client-Dropped-P0-Spans"));
      assertEquals(emptyList(), convertList(agentVersion, agent.getLastRequest().getBody()));
    } finally {
      agent.close();
    }
  }

  @Test
  void testContentIsSentAsMsgpackServiceSpan() throws IOException {
    String agentVersion = "v0.4/traces";
    DDSpan span =
        buildSpan(
            1L,
            "service.name",
            "my-service",
            PropagationTags.factory()
                .fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.usr=123"));
    span.finish();
    setDurationNano(span, 10L);
    List<List<DDSpan>> traces = singletonList(singletonList(span));

    JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            s -> s.handlers(h -> h.put(agentVersion, api -> api.getResponse().send())));
    try {
      DDAgentApi client = createAgentApi(agent.getAddress().toString()).api;
      Payload payload = prepareTraces(agentVersion, traces);
      assertTrue(client.sendSerializedTraces(payload).success());
      assertEquals("application/msgpack", agent.getLastRequest().getContentType());
      assertEquals(
          "true", agent.getLastRequest().getHeaders().get("Datadog-Client-Computed-Top-Level"));
      assertEquals("java", agent.getLastRequest().getHeaders().get("Datadog-Meta-Lang"));
      assertEquals(
          System.getProperty("java.version", "unknown"),
          agent.getLastRequest().getHeaders().get("Datadog-Meta-Lang-Version"));
      assertEquals(
          "Stubbed-Test-Version",
          agent.getLastRequest().getHeaders().get("Datadog-Meta-Tracer-Version"));
      assertEquals(
          String.valueOf(traces.size()),
          agent.getLastRequest().getHeaders().get("X-Datadog-Trace-Count"));
      assertEquals(
          String.valueOf(payload.droppedTraces()),
          agent.getLastRequest().getHeaders().get("Datadog-Client-Dropped-P0-Traces"));
      assertEquals(
          String.valueOf(payload.droppedSpans()),
          agent.getLastRequest().getHeaders().get("Datadog-Client-Dropped-P0-Spans"));

      Map<String, Object> meta = new TreeMap<>();
      meta.put("thread.name", Thread.currentThread().getName());
      meta.put("_dd.p.usr", "123");
      meta.put("_dd.p.dm", "-1");
      meta.put("_dd.p.ksr", "1");
      meta.put("_dd.svc_src", "m");
      if (Config.get().isExperimentalPropagateProcessTagsEnabled()
          && ProcessTags.getTagsForSerialization() != null) {
        meta.put("_dd.tags.process", ProcessTags.getTagsForSerialization().toString());
      }
      Map<String, Object> metrics = new TreeMap<>();
      metrics.put(DDSpanContext.PRIORITY_SAMPLING_KEY, 1);
      metrics.put(InstrumentationTags.DD_TOP_LEVEL.toString(), 1);
      metrics.put(RateByServiceTraceSampler.SAMPLING_AGENT_RATE, 1.0);
      metrics.put("thread.id", Thread.currentThread().getId());
      Map<String, Object> spanMap = new TreeMap<>();
      spanMap.put("duration", 10);
      spanMap.put("error", 0);
      spanMap.put("meta", meta);
      spanMap.put("metrics", metrics);
      spanMap.put("name", "fakeOperation");
      spanMap.put("parent_id", 0);
      spanMap.put("resource", "fakeResource");
      spanMap.put("service", "my-service");
      spanMap.put("span_id", 1);
      spanMap.put("start", 1000);
      spanMap.put("trace_id", 1);
      spanMap.put("type", "fakeType");
      List<List<Map<String, Object>>> expectedRequestBody = singletonList(singletonList(spanMap));
      assertDeepEquals(
          expectedRequestBody, convertList(agentVersion, agent.getLastRequest().getBody()));
    } finally {
      agent.close();
    }
  }

  @Test
  void testContentIsSentAsMsgpackResourceSpan() throws IOException {
    String agentVersion = "v0.4/traces";
    DDSpan span =
        buildSpan(
            100L,
            "resource.name",
            "my-resource",
            PropagationTags.factory()
                .fromHeaderValue(PropagationTags.HeaderType.DATADOG, "_dd.p.usr=123"));
    span.finish();
    setDurationNano(span, 10L);
    List<List<DDSpan>> traces = singletonList(singletonList(span));

    JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            s -> s.handlers(h -> h.put(agentVersion, api -> api.getResponse().send())));
    try {
      DDAgentApi client = createAgentApi(agent.getAddress().toString()).api;
      Payload payload = prepareTraces(agentVersion, traces);
      assertTrue(client.sendSerializedTraces(payload).success());
      assertEquals("application/msgpack", agent.getLastRequest().getContentType());
      assertEquals(
          "true", agent.getLastRequest().getHeaders().get("Datadog-Client-Computed-Top-Level"));
      assertEquals("java", agent.getLastRequest().getHeaders().get("Datadog-Meta-Lang"));
      assertEquals(
          System.getProperty("java.version", "unknown"),
          agent.getLastRequest().getHeaders().get("Datadog-Meta-Lang-Version"));
      assertEquals(
          "Stubbed-Test-Version",
          agent.getLastRequest().getHeaders().get("Datadog-Meta-Tracer-Version"));
      assertEquals(
          String.valueOf(traces.size()),
          agent.getLastRequest().getHeaders().get("X-Datadog-Trace-Count"));
      assertEquals(
          String.valueOf(payload.droppedTraces()),
          agent.getLastRequest().getHeaders().get("Datadog-Client-Dropped-P0-Traces"));
      assertEquals(
          String.valueOf(payload.droppedSpans()),
          agent.getLastRequest().getHeaders().get("Datadog-Client-Dropped-P0-Spans"));

      Map<String, Object> meta = new TreeMap<>();
      meta.put("thread.name", Thread.currentThread().getName());
      meta.put("_dd.p.usr", "123");
      meta.put("_dd.p.dm", "-1");
      meta.put("_dd.p.ksr", "1");
      if (Config.get().isExperimentalPropagateProcessTagsEnabled()
          && ProcessTags.getTagsForSerialization() != null) {
        meta.put("_dd.tags.process", ProcessTags.getTagsForSerialization().toString());
      }
      Map<String, Object> metrics = new TreeMap<>();
      metrics.put(DDSpanContext.PRIORITY_SAMPLING_KEY, 1);
      metrics.put(InstrumentationTags.DD_TOP_LEVEL.toString(), 1);
      metrics.put(RateByServiceTraceSampler.SAMPLING_AGENT_RATE, 1.0);
      metrics.put("thread.id", Thread.currentThread().getId());
      Map<String, Object> spanMap = new TreeMap<>();
      spanMap.put("duration", 10);
      spanMap.put("error", 0);
      spanMap.put("meta", meta);
      spanMap.put("metrics", metrics);
      spanMap.put("name", "fakeOperation");
      spanMap.put("parent_id", 0);
      spanMap.put("resource", "my-resource");
      spanMap.put("service", "fakeService");
      spanMap.put("span_id", 1);
      spanMap.put("start", 100000);
      spanMap.put("trace_id", 1);
      spanMap.put("type", "fakeType");
      List<List<Map<String, Object>>> expectedRequestBody = singletonList(singletonList(spanMap));
      assertDeepEquals(
          expectedRequestBody, convertList(agentVersion, agent.getLastRequest().getBody()));
    } finally {
      agent.close();
    }
  }

  @TableTest({
    "scenario    | agentVersion ",
    "v0.3 traces | 'v0.3/traces'",
    "v0.4 traces | 'v0.4/traces'",
    "v0.5 traces | 'v0.5/traces'"
  })
  void testApiResponseListenersSee200Responses(String agentVersion) {
    AtomicReference<Map<String, Map<String, Number>>> agentResponse = new AtomicReference<>(null);
    RemoteResponseListener responseListener =
        (endpoint, responseJson) -> agentResponse.set(responseJson);

    JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.put(
                            agentVersion,
                            api -> {
                              int status = api.getRequest().getContentLength() > 0 ? 200 : 500;
                              api.getResponse().status(status).send("{\"hello\":{}}");
                            })));
    try {
      DDAgentApi client = createAgentApi(agent.getAddress().toString()).api;
      client.addResponseListener(responseListener);
      List<List<DDSpan>> traces = Arrays.asList(emptyList(), emptyList(), emptyList());
      Payload payload = prepareTraces(agentVersion, traces);
      payload.withDroppedTraces(1);
      payload.withDroppedTraces(3);

      client.sendSerializedTraces(payload);

      Map<String, Map<String, Number>> response = agentResponse.get();
      assertTrue(response != null && response.containsKey("hello"));
      assertEquals("java", agent.getLastRequest().getHeaders().get("Datadog-Meta-Lang"));
      assertEquals(
          System.getProperty("java.version", "unknown"),
          agent.getLastRequest().getHeaders().get("Datadog-Meta-Lang-Version"));
      assertEquals(
          "Stubbed-Test-Version",
          agent.getLastRequest().getHeaders().get("Datadog-Meta-Tracer-Version"));
      assertEquals("3", agent.getLastRequest().getHeaders().get("X-Datadog-Trace-Count"));
      assertEquals(
          String.valueOf(payload.droppedTraces()),
          agent.getLastRequest().getHeaders().get("Datadog-Client-Dropped-P0-Traces"));
      assertEquals(
          String.valueOf(payload.droppedSpans()),
          agent.getLastRequest().getHeaders().get("Datadog-Client-Dropped-P0-Spans"));
    } finally {
      agent.close();
    }
  }

  @Test
  void testApiDowngradesToV3IfV04NotAvailable() {
    JavaTestHttpServer v3Agent =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.put(
                            "v0.3/traces",
                            api -> {
                              int status = api.getRequest().getContentLength() > 0 ? 200 : 500;
                              api.getResponse().status(status).send();
                            })));
    try {
      DDAgentApi client = createAgentApi(v3Agent.getAddress().toString()).api;
      Payload payload = prepareTraces("v0.4/traces", emptyList());
      assertTrue(client.sendSerializedTraces(payload).success());
      assertEquals("/v0.3/traces", v3Agent.getLastRequest().getPath());
    } finally {
      v3Agent.close();
    }
  }

  @TableTest({
    "scenario         | endpointVersion | delayTrace | badPort",
    "v0.4 ok          | 'v0.4'          | 0          | false  ",
    "v0.3 bad port    | 'v0.3'          | 0          | true   ",
    "v0.4 short delay | 'v0.4'          | 500        | false  ",
    "v0.3 long delay  | 'v0.3'          | 30000      | false  "
  })
  void testApiDowngradesToV3IfTimeoutExceeded(
      String endpointVersion, int delayTrace, boolean badPort) {
    JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h -> {
                      h.put(
                          "v0.3/traces",
                          api -> {
                            int status = api.getRequest().getContentLength() > 0 ? 200 : 500;
                            api.getResponse().status(status).send();
                          });
                      h.put(
                          "v0.4/traces",
                          api -> {
                            if (delayTrace > 0) {
                              try {
                                Thread.sleep(delayTrace);
                              } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                              }
                            }
                            int status = api.getRequest().getContentLength() > 0 ? 200 : 500;
                            api.getResponse().status(status).send();
                          });
                    }));
    try {
      int port = badPort ? 999 : agent.getAddress().getPort();
      String url = "http://" + agent.getAddress().getHost() + ":" + port;
      DDAgentApi client = createAgentApi(url).api;
      Payload payload = prepareTraces("v0.4/traces", emptyList());
      Response result = client.sendSerializedTraces(payload);
      assertEquals(!badPort, result.success());
      if (!badPort) {
        assertEquals("/" + endpointVersion + "/traces", agent.getLastRequest().getPath());
      }
    } finally {
      agent.close();
    }
  }

  // all the tested traces are empty and it just so happens that
  // arrays and maps take the same amount of space in messagepack, so
  // all the sizes match, except in v0.5 where there is 1 byte for a
  // 2 element array header and 1 byte for an empty dictionary
  @TableTest({
    "scenario          | agentVersion  | expectedLength | traceCount",
    "v0.4 empty        | 'v0.4/traces' | 1              | 0         ",
    "v0.4 2 traces     | 'v0.4/traces' | 3              | 2         ",
    "v0.4 15 traces    | 'v0.4/traces' | 16             | 15        ",
    "v0.4 16 traces    | 'v0.4/traces' | 19             | 16        ",
    "v0.4 65535 traces | 'v0.4/traces' | 65538          | 65535     ",
    "v0.4 65536 traces | 'v0.4/traces' | 65541          | 65536     ",
    "v0.5 empty        | 'v0.5/traces' | 3              | 0         ",
    "v0.5 2 traces     | 'v0.5/traces' | 5              | 2         ",
    "v0.5 15 traces    | 'v0.5/traces' | 18             | 15        ",
    "v0.5 16 traces    | 'v0.5/traces' | 21             | 16        ",
    "v0.5 65535 traces | 'v0.5/traces' | 65540          | 65535     ",
    "v0.5 65536 traces | 'v0.5/traces' | 65543          | 65536     "
  })
  void testVerifyContentLength(String agentVersion, long expectedLength, int traceCount) {
    List<List<DDSpan>> traces = generateEmptyTraces(traceCount);
    AtomicLong receivedContentLength = new AtomicLong();
    JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            s ->
                s.handlers(
                    h ->
                        h.put(
                            agentVersion,
                            api -> {
                              receivedContentLength.set(api.getRequest().getContentLength());
                              api.getResponse().status(200).send();
                            })));
    try {
      DDAgentApi client = createAgentApi(agent.getAddress().toString()).api;
      Payload payload = prepareTraces(agentVersion, traces);
      assertTrue(client.sendSerializedTraces(payload).success());
      assertEquals(expectedLength, receivedContentLength.get());
    } finally {
      agent.close();
    }
  }

  @Test
  void testEmbeddedHttpClientRejectsAsyncRequests() throws Exception {
    JavaTestHttpServer agent = newAgent("v0.5/traces");
    try {
      AgentApiPair pair = createAgentApi(agent.getAddress().toString());
      pair.discovery.discover();
      Field httpClientField = DDAgentApi.class.getDeclaredField("httpClient");
      httpClientField.setAccessible(true);
      OkHttpClient httpClient = (OkHttpClient) httpClientField.get(pair.api);
      ExecutorService httpExecutorService = httpClient.dispatcher().executorService();
      assertThrows(RejectedExecutionException.class, () -> httpExecutorService.execute(() -> {}));
      assertTrue(httpExecutorService.isShutdown());
    } finally {
      agent.close();
    }
  }

  @Test
  void testMetaStructSupportOnTheEncodedSpans() throws IOException {
    String agentVersion = "v0.4/traces";
    JavaTestHttpServer agent =
        JavaTestHttpServer.httpServer(
            s -> s.handlers(h -> h.put(agentVersion, api -> api.getResponse().send())));
    try {
      DDAgentApi client = createAgentApi(agent.getAddress().toString()).api;
      DDSpan span = buildSpan(1L, "fakeType", Collections.emptyMap());
      span.setMetaStruct("meta_1", "Hello World!");
      Map<String, Object> meta2 = new HashMap<>();
      meta2.put("Hello", " World!");
      span.setMetaStruct("meta_2", meta2);
      Payload payload = prepareTraces(agentVersion, singletonList(singletonList(span)));
      assertTrue(client.sendSerializedTraces(payload).success());

      List<List<TreeMap<String, Object>>> body =
          convertList(agentVersion, agent.getLastRequest().getBody());
      @SuppressWarnings("unchecked")
      Map<String, byte[]> metaStruct = (Map<String, byte[]>) body.get(0).get(0).get("meta_struct");
      assertEquals(2, metaStruct.size());
      assertEquals("Hello World!", mapper.readValue(metaStruct.get("meta_1"), String.class));
      @SuppressWarnings("unchecked")
      Map<String, Object> actualMeta2 = mapper.readValue(metaStruct.get("meta_2"), Map.class);
      assertEquals(meta2, actualMeta2);
    } finally {
      agent.close();
    }
  }

  // --- Inner types ---

  static class AgentApiPair {
    final DDAgentFeaturesDiscovery discovery;
    final DDAgentApi api;

    AgentApiPair(DDAgentFeaturesDiscovery discovery, DDAgentApi api) {
      this.discovery = discovery;
      this.api = api;
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

  // --- Helper methods ---

  AgentApiPair createAgentApi(String url, ProtocolVersion protocolVersion) {
    HttpUrl agentUrl = HttpUrl.get(url);
    OkHttpClient client = OkHttpUtils.buildHttpClient(agentUrl, 1000);
    DDAgentFeaturesDiscovery discovery =
        new DDAgentFeaturesDiscovery(client, monitoring, agentUrl, protocolVersion, true, false);
    return new AgentApiPair(
        discovery, new DDAgentApi(client, agentUrl, discovery, monitoring, false));
  }

  AgentApiPair createAgentApi(String url) {
    return createAgentApi(url, V0_5);
  }

  Payload prepareTraces(String agentVersion, List<List<DDSpan>> traces) {
    TracesCapture traceCapture = new TracesCapture();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1 << 20, traceCapture));

    TraceMapper traceMapper;
    if ("v1.0/traces".equals(agentVersion)) {
      traceMapper = new TraceMapperV1();
    } else if ("v0.5/traces".equals(agentVersion)) {
      traceMapper = new TraceMapperV0_5();
    } else {
      traceMapper = new TraceMapperV0_4();
    }

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

  static List<List<TreeMap<String, Object>>> convertList(String agentVersion, byte[] bytes)
      throws IOException {
    if ("v0.5/traces".equals(agentVersion)) {
      return convertListV5(bytes);
    }
    List<List<TreeMap<String, Object>>> returnVal =
        mapper.readValue(bytes, new TypeReference<List<List<TreeMap<String, Object>>>>() {});
    for (List<TreeMap<String, Object>> trace : returnVal) {
      for (TreeMap<String, Object> span : trace) {
        @SuppressWarnings("unchecked")
        Map<String, Object> meta = (Map<String, Object>) span.get("meta");
        if (meta != null) {
          meta.remove("runtime-id");
          meta.remove("language");
        }
      }
    }
    return returnVal;
  }

  static List<List<TreeMap<String, Object>>> convertListV5(byte[] bytes) throws IOException {
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

          @SuppressWarnings("unchecked")
          Map<Object, Object> meta = (Map<Object, Object>) map.get("meta");
          if (meta != null) {
            meta.remove("runtime-id");
            meta.remove("language");
          }
        }
        mapTrace.add(map);
      }
      maps.add(mapTrace);
    }
    return maps;
  }

  static List<List<DDSpan>> generateEmptyTraces(int count) {
    List<List<DDSpan>> traces = new ArrayList<>(count);
    for (int i = 0; i < count; i++) {
      traces.add(emptyList());
    }
    return traces;
  }

  static void setDurationNano(DDSpan span, long duration) {
    try {
      Field field = DDSpan.class.getDeclaredField("durationNano");
      field.setAccessible(true);
      field.setLong(span, duration);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      Assertions.fail("Could not set durationNano: " + e.getMessage());
    }
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
}
