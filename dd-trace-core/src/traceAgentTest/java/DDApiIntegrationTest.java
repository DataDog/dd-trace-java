import static datadog.trace.api.ProtocolVersion.V0_5;
import static datadog.trace.api.ProtocolVersion.V1_0;
import static java.util.Collections.emptyList;
import static java.util.Collections.singletonList;
import static java.util.stream.Collectors.toList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.communication.ddagent.DDAgentFeaturesDiscovery;
import datadog.communication.http.OkHttpUtils;
import datadog.communication.serialization.ByteBufferConsumer;
import datadog.communication.serialization.FlushingBuffer;
import datadog.communication.serialization.msgpack.MsgPackWriter;
import datadog.metrics.api.statsd.StatsDClient;
import datadog.metrics.impl.MonitoringImpl;
import datadog.trace.api.Config;
import datadog.trace.api.ProtocolVersion;
import datadog.trace.common.writer.ListWriter;
import datadog.trace.common.writer.Payload;
import datadog.trace.common.writer.RemoteApi;
import datadog.trace.common.writer.RemoteResponseListener;
import datadog.trace.common.writer.ddagent.DDAgentApi;
import datadog.trace.common.writer.ddagent.TraceMapper;
import datadog.trace.common.writer.ddagent.TraceMapperV0_4;
import datadog.trace.common.writer.ddagent.TraceMapperV0_5;
import datadog.trace.common.writer.ddagent.TraceMapperV1;
import datadog.trace.core.CoreTracer;
import datadog.trace.core.DDSpan;
import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
  String traceEndpoint;

  AtomicReference<String> endpoint = new AtomicReference<>(null);
  AtomicReference<Map<String, Map<String, Number>>> agentResponse = new AtomicReference<>(null);

  RemoteResponseListener responseListener =
      (receivedEndpoint, responseJson) -> {
        endpoint.set(receivedEndpoint);
        agentResponse.set(responseJson);
      };

  @BeforeAll
  static void startSocatProxy() throws IOException {
    File tmpDir = Files.createTempDirectory("dd-api-integration-test").toFile();
    tmpDir.deleteOnExit();
    socketPath = new File(tmpDir, "socket");
    System.out.println(
        "!!!socat UNIX-LISTEN:"
            + socketPath
            + ",reuseaddr,fork TCP-CONNECT:"
            + getAgentContainerHost()
            + ":"
            + getAgentContainerPort());
    process =
        Runtime.getRuntime()
            .exec(
                "socat UNIX-LISTEN:"
                    + socketPath
                    + ",reuseaddr,fork TCP-CONNECT:"
                    + getAgentContainerHost()
                    + ":"
                    + getAgentContainerPort());
  }

  @BeforeEach
  void initTracer() throws InterruptedException {
    tracer = CoreTracer.builder().writer(new ListWriter()).build();
    span = (DDSpan) tracer.buildSpan("datadog", "fakeOperation").start();
    Thread.sleep(1);
    span.finish();
  }

  @AfterEach
  void cleanup() {
    if (tracer != null) {
      tracer.close();
    }
  }

  @AfterAll
  static void stopSocatProxy() {
    if (process != null) {
      process.destroy();
    }
  }

  void beforeTest(ProtocolVersion protocol) {
    MonitoringImpl monitoring = new MonitoringImpl(StatsDClient.NO_OP, 1, TimeUnit.SECONDS);
    HttpUrl agentUrl = HttpUrl.get(Config.get().getAgentUrl());
    OkHttpClient httpClient = OkHttpUtils.buildHttpClient(agentUrl, 5000);
    discovery =
        new DDAgentFeaturesDiscovery(httpClient, monitoring, agentUrl, protocol, true, false);
    api = new DDAgentApi(httpClient, agentUrl, discovery, monitoring, false);
    api.addResponseListener(responseListener);
    HttpUrl udsAgentUrl = HttpUrl.get(String.format("http://%s:%d", SOMEHOST, SOMEPORT));
    OkHttpClient udsClient = OkHttpUtils.buildHttpClient(true, socketPath.toString(), null, 5000);
    udsDiscovery =
        new DDAgentFeaturesDiscovery(udsClient, monitoring, agentUrl, protocol, true, false);
    unixDomainSocketApi = new DDAgentApi(udsClient, udsAgentUrl, udsDiscovery, monitoring, false);
    unixDomainSocketApi.addResponseListener(responseListener);
    if (protocol == V1_0) {
      mapper = new TraceMapperV1();
    } else if (protocol == V0_5) {
      mapper = new TraceMapperV0_5();
    } else {
      mapper = new TraceMapperV0_4();
    }
    traceEndpoint = protocol.endpoint();
  }

  @TableTest({
    "scenario        | traceCount | protocol",
    "empty traces    | 0          | V0_5    ",
    "16 empty traces | 16         | V0_5    ",
    "empty traces    | 0          | V0_4    ",
    "16 empty traces | 16         | V0_4    "
  })
  void sendingEmptyTracesSucceeds(int traceCount, ProtocolVersion protocol) throws IOException {
    beforeTest(protocol);

    RemoteApi.Response response =
        api.sendSerializedTraces(prepareRequest(emptyTraces(traceCount), mapper));
    assertFalse(response.response().isEmpty());
    assertFalse(response.exception().isPresent());
    assertTrue(response.status().isPresent());
    assertEquals(200, response.status().getAsInt());
    assertTrue(response.success());
    assertEquals(traceEndpoint, discovery.getTraceEndpoint());
    assertEquals(Config.get().getAgentUrl() + "/" + traceEndpoint, endpoint.get());
    assertInstanceOf(Map.class, agentResponse.get().get("rate_by_service"));
  }

  @TableTest({
    "scenario | protocol",
    "V0_5     | V0_5    ",
    "V0_4     | V0_4    "
  })
  void sendingTracesSucceeds(ProtocolVersion protocol) throws IOException {
    beforeTest(protocol);

    RemoteApi.Response response =
        api.sendSerializedTraces(prepareRequest(singletonList(singletonList(span)), mapper));
    assertFalse(response.response().isEmpty());
    assertFalse(response.exception().isPresent());
    assertTrue(response.status().isPresent());
    assertEquals(200, response.status().getAsInt());
    assertTrue(response.success());
    assertEquals(traceEndpoint, discovery.getTraceEndpoint());
    assertEquals(Config.get().getAgentUrl() + "/" + traceEndpoint, endpoint.get());
    assertInstanceOf(Map.class, agentResponse.get().get("rate_by_service"));
  }

  @TableTest({
    "scenario     | protocol",
    "empty traces | V0_5    ",
    "empty traces | V0_4    "
  })
  void sendingEmptyTracesToUnixDomainSocketSucceeds(ProtocolVersion protocol) throws IOException {
    beforeTest(protocol);

    RemoteApi.Response response =
        unixDomainSocketApi.sendSerializedTraces(prepareRequest(emptyList(), mapper));
    assertFalse(response.response().isEmpty());
    assertFalse(response.exception().isPresent());
    assertTrue(response.status().isPresent());
    assertEquals(200, response.status().getAsInt());
    assertTrue(response.success());
    assertEquals(traceEndpoint, udsDiscovery.getTraceEndpoint());
    assertEquals("http://" + SOMEHOST + ":" + SOMEPORT + "/" + traceEndpoint, endpoint.get());
    assertInstanceOf(Map.class, agentResponse.get().get("rate_by_service"));
  }

  @TableTest({
    "scenario | protocol",
    "V0_5     | V0_5    ",
    "V0_4     | V0_4    "
  })
  void sendingTracesToUnixDomainSocketSucceeds(ProtocolVersion protocol) throws IOException {
    beforeTest(protocol);

    RemoteApi.Response response =
        unixDomainSocketApi.sendSerializedTraces(
            prepareRequest(singletonList(singletonList(span)), mapper));
    assertFalse(response.response().isEmpty());
    assertFalse(response.exception().isPresent());
    assertTrue(response.status().isPresent());
    assertEquals(200, response.status().getAsInt());
    assertTrue(response.success());
    assertEquals(traceEndpoint, udsDiscovery.getTraceEndpoint());
    assertEquals("http://" + SOMEHOST + ":" + SOMEPORT + "/" + traceEndpoint, endpoint.get());
    assertInstanceOf(Map.class, agentResponse.get().get("rate_by_service"));
  }

  private static List<List<DDSpan>> emptyTraces(int count) {
    return Stream.generate(Collections::<DDSpan>emptyList).limit(count).collect(toList());
  }

  Payload prepareRequest(List<List<DDSpan>> traces, TraceMapper traceMapper) throws IOException {
    Traces traceCapture = new Traces();
    MsgPackWriter packer = new MsgPackWriter(new FlushingBuffer(1 << 10, traceCapture));
    for (List<DDSpan> trace : traces) {
      packer.format(trace, traceMapper);
    }
    packer.flush();
    return traceMapper.newPayload().withBody(traceCapture.traceCount, traceCapture.buffer);
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
}
