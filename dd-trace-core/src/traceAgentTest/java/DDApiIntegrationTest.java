import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.api.Config;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.common.writer.RemoteResponseListener;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.TraceMapper;
import datadog.trace.common.writer.ddagent.TraceMapperV0_4;
import datadog.trace.common.writer.ddagent.TraceMapperV0_5;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.tabletest.junit.TableTest;

class DDApiIntegrationTest extends AbstractTraceAgentTest {

  // Looks like okHttp needs to resolve this, even for connection over socket
  static final String SOMEHOST = "datadoghq.com";
  static final int SOMEPORT = 123;

  static Process process;
  static File socketPath;

  CoreTracer tracer;
  DDSpan span;

  DDAgentFeaturesDiscovery discovery;
  DDAgentFeaturesDiscovery udsDiscovery;
  DDAgentApi api;
  DDAgentApi unixDomainSocketApi;
  TraceMapper mapper;
  String version;

  AtomicReference<String> endpoint = new AtomicReference<>(null);
  AtomicReference<Map<String, Map<String, Number>>> agentResponse = new AtomicReference<>(null);

  RemoteResponseListener responseListener =
      (receivedEndpoint, responseJson) -> {
        endpoint.set(receivedEndpoint);
        agentResponse.set(responseJson);
      };

  @BeforeAll
  static void setupSpec() throws Exception {
    File tmpDir = createTempDir();
    tmpDir.deleteOnExit();
    socketPath = new File(tmpDir, "socket");
    System.out.println(
        "!!!socat UNIX-LISEN:"
            + socketPath
            + ",reuseaddr,fork TCP-CONNECT:"
            + staticGetAgentContainerHost()
            + ":"
            + staticGetAgentContainerPort());
    process =
        Runtime.getRuntime()
            .exec(
                "socat UNIX-LISTEN:"
                    + socketPath
                    + ",reuseaddr,fork TCP-CONNECT:"
                    + staticGetAgentContainerHost()
                    + ":"
                    + staticGetAgentContainerPort());
  }

  private static File createTempDir() throws Exception {
    File tmpDir = File.createTempFile("dd-trace-agent-test", "");
    tmpDir.delete();
    tmpDir.mkdirs();
    return tmpDir;
  }

  private static String staticGetAgentContainerHost() {
    if (agentContainer != null) {
      return agentContainer.getHost();
    }
    return System.getenv("CI_AGENT_HOST");
  }

  private static String staticGetAgentContainerPort() {
    if (agentContainer != null) {
      return String.valueOf(
          agentContainer.getMappedPort(datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT));
    }
    return String.valueOf(datadog.trace.api.ConfigDefaults.DEFAULT_TRACE_AGENT_PORT);
  }

  @BeforeEach
  @Override
  void setup() throws Exception {
    super.setup();
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
    span = tracer.buildSpan("fakeOperation").start();
    Thread.sleep(1);
    span.finish();
  }

  @AfterEach
  void cleanup() throws Exception {
    if (tracer != null) {
      tracer.close();
    }
  }

  @AfterAll
  static void cleanupSpec() {
    if (process != null) {
      process.destroy();
    }
  }

  void beforeTest(boolean enableV05) {
    MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
    HttpUrl agentUrl = HttpUrl.get(Config.get().getAgentUrl());
    OkHttpClient httpClient = OkHttpUtils.buildHttpClient(agentUrl, 5000);
    discovery = new DDAgentFeaturesDiscovery(httpClient, monitoring, agentUrl, enableV05, true);
    api = new DDAgentApi(httpClient, agentUrl, discovery, monitoring, false);
    api.addResponseListener(responseListener);
    HttpUrl udsAgentUrl = HttpUrl.get(String.format("http://%s:%d", SOMEHOST, SOMEPORT));
    OkHttpClient udsClient = OkHttpUtils.buildHttpClient(true, socketPath.toString(), null, 5000);
    udsDiscovery = new DDAgentFeaturesDiscovery(udsClient, monitoring, agentUrl, enableV05, true);
    unixDomainSocketApi = new DDAgentApi(udsClient, udsAgentUrl, udsDiscovery, monitoring, false);
    unixDomainSocketApi.addResponseListener(responseListener);
    mapper = enableV05 ? new TraceMapperV0_5() : new TraceMapperV0_4();
    version = enableV05 ? "v0.5" : "v0.4";
  }

  // spotless:off
  @TableTest({
    "scenario                     | traces                         | enableV05",
    "empty traces v0.5 test 1     | EMPTY                          | true     ",
    "16 empty traces v0.5 test 4  | SIXTEEN_EMPTY                  | true     ",
    "empty traces v0.4 test 5     | EMPTY                          | false    ",
    "16 empty traces v0.4 test 8  | SIXTEEN_EMPTY                  | false    ",
  })
  // spotless:on
  @ParameterizedTest(name = "[{index}] Sending empty traces succeeds - {0}")
  void sendingEmptyTracesSucceeds(String scenario, String tracesKey, boolean enableV05) {
    List<List<DDSpan>> traces = resolveTraces(tracesKey);
    beforeTest(enableV05);
    RemoteApi.Response response = api.sendSerializedTraces(prepareRequest(traces, mapper));
    assertFalse(response.response().isEmpty());
    assertFalse(response.exception().isPresent());
    assertTrue(response.status().isPresent());
    assertEquals(200, response.status().getAsInt());
    assertTrue(response.success());
    assertEquals(version + "/traces", discovery.getTraceEndpoint());
    assertEquals(Config.get().getAgentUrl() + "/" + version + "/traces", endpoint.get());
    assertNotNull(agentResponse.get());
    assertTrue(agentResponse.get().get("rate_by_service") instanceof Map);
  }

  @TableTest({
    "scenario    | enableV05",
    "v0.5 traces | true     ",
    "v0.4 traces | false    "
  })
  @ParameterizedTest(name = "[{index}] Sending traces succeeds - {0}")
  void sendingTracesSucceeds(String scenario, boolean enableV05) {
    beforeTest(enableV05);
    List<List<DDSpan>> traces =
        java.util.Collections.singletonList(java.util.Collections.singletonList(span));
    RemoteApi.Response response = api.sendSerializedTraces(prepareRequest(traces, mapper));
    assertFalse(response.response().isEmpty());
    assertFalse(response.exception().isPresent());
    assertTrue(response.status().isPresent());
    assertEquals(200, response.status().getAsInt());
    assertTrue(response.success());
    assertEquals(version + "/traces", discovery.getTraceEndpoint());
    assertEquals(Config.get().getAgentUrl() + "/" + version + "/traces", endpoint.get());
    assertNotNull(agentResponse.get());
    assertTrue(agentResponse.get().get("rate_by_service") instanceof Map);
  }

  // spotless:off
  @TableTest({
    "scenario                         | traces        | enableV05",
    "empty traces v0.5 uds test 1     | EMPTY         | true     ",
    "empty traces v0.4 uds test 3     | EMPTY         | false    ",
  })
  // spotless:on
  @ParameterizedTest(name = "[{index}] Sending empty traces to unix domain socket succeeds - {0}")
  void sendingEmptyTracesToUnixDomainSocketSucceeds(
      String scenario, String tracesKey, boolean enableV05) {
    List<List<DDSpan>> traces = resolveTraces(tracesKey);
    beforeTest(enableV05);
    RemoteApi.Response response =
        unixDomainSocketApi.sendSerializedTraces(prepareRequest(traces, mapper));
    assertFalse(response.response().isEmpty());
    assertFalse(response.exception().isPresent());
    assertTrue(response.status().isPresent());
    assertEquals(200, response.status().getAsInt());
    assertTrue(response.success());
    assertEquals(version + "/traces", udsDiscovery.getTraceEndpoint());
    assertEquals("http://" + SOMEHOST + ":" + SOMEPORT + "/" + version + "/traces", endpoint.get());
    assertNotNull(agentResponse.get());
    assertTrue(agentResponse.get().get("rate_by_service") instanceof Map);
  }

  @TableTest({
    "scenario                     | enableV05",
    "uds traces v0.5 enableV05    | true     ",
    "uds traces v0.4 no enableV05 | false    "
  })
  @ParameterizedTest(name = "[{index}] Sending traces to unix domain socket succeeds - {0}")
  void sendingTracesToUnixDomainSocketSucceeds(String scenario, boolean enableV05) {
    beforeTest(enableV05);
    List<List<DDSpan>> traces =
        java.util.Collections.singletonList(java.util.Collections.singletonList(span));
    RemoteApi.Response response =
        unixDomainSocketApi.sendSerializedTraces(prepareRequest(traces, mapper));
    assertFalse(response.response().isEmpty());
    assertFalse(response.exception().isPresent());
    assertTrue(response.status().isPresent());
    assertEquals(200, response.status().getAsInt());
    assertTrue(response.success());
    assertEquals(version + "/traces", udsDiscovery.getTraceEndpoint());
    assertEquals("http://" + SOMEHOST + ":" + SOMEPORT + "/" + version + "/traces", endpoint.get());
    assertNotNull(agentResponse.get());
    assertTrue(agentResponse.get().get("rate_by_service") instanceof Map);
  }

  private List<List<DDSpan>> resolveTraces(String key) {
    if ("EMPTY".equals(key)) {
      return java.util.Collections.emptyList();
    } else if ("SIXTEEN_EMPTY".equals(key)) {
      List<List<DDSpan>> result = new java.util.ArrayList<>();
      for (int i = 0; i < 16; i++) {
        result.add(java.util.Collections.<DDSpan>emptyList());
      }
      return result;
    }
    return java.util.Collections.emptyList();
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

  Payload prepareRequest(List<List<DDSpan>> traces, TraceMapper traceMapper) {
    Traces traceCapture = new Traces();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1 << 10, traceCapture));
    for (List<DDSpan> trace : traces) {
      packer.format(trace, traceMapper);
    }
    packer.flush();
    return traceMapper.newPayload().withBody(traceCapture.traceCount, traceCapture.buffer);
  }
}
