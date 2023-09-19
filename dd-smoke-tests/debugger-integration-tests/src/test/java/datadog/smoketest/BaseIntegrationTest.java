package datadog.smoketest;

import static datadog.smoketest.ProcessBuilderHelper.buildDirectory;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.datadog.debugger.agent.Configuration;
import com.datadog.debugger.agent.JsonSnapshotSerializer;
import com.datadog.debugger.agent.ProbeStatus;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.sink.Snapshot;
import com.datadog.debugger.util.MoshiHelper;
import com.datadog.debugger.util.MoshiSnapshotTestHelper;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.test.agent.decoder.DecodedMessage;
import datadog.trace.test.agent.decoder.DecodedSpan;
import datadog.trace.test.agent.decoder.DecodedTrace;
import datadog.trace.test.agent.decoder.Decoder;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import okhttp3.HttpUrl;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okio.ByteString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class BaseIntegrationTest {
  protected static final Logger LOG = LoggerFactory.getLogger(BaseIntegrationTest.class);
  protected static final String TRACE_URL_PATH = "/v0.4/traces";
  protected static final String CONFIG_URL_PATH = "/v0.7/config";
  protected static final String SNAPSHOT_URL_PATH = "/debugger/v1/input";
  protected static final int REQUEST_WAIT_TIMEOUT = 10;
  private static final Path LOG_FILE_BASE =
      Paths.get(
          buildDirectory(),
          "reports",
          "testProcess." + SimpleAppDebuggerIntegrationTest.class.getName());
  private static final String INFO_CONTENT =
      "{\"endpoints\": [\""
          + TRACE_URL_PATH
          + "\", \""
          + SNAPSHOT_URL_PATH
          + "\", \""
          + CONFIG_URL_PATH
          + "\"]}";
  private static final MockResponse AGENT_INFO_RESPONSE =
      new MockResponse().setResponseCode(200).setBody(INFO_CONTENT);
  private static final MockResponse TELEMETRY_RESPONSE = new MockResponse().setResponseCode(202);
  protected static final MockResponse EMPTY_200_RESPONSE = new MockResponse().setResponseCode(200);

  private static final ByteString DIAGNOSTICS_STR = ByteString.encodeUtf8("diagnostics");

  protected MockWebServer datadogAgentServer;
  private MockDispatcher probeMockDispatcher;
  private StatsDServer statsDServer;
  private HttpUrl probeUrl;
  private HttpUrl snapshotUrl;
  protected Path logFilePath;
  protected Process targetProcess;
  private Configuration currentConfiguration;
  private boolean configProvided;
  protected final Object configLock = new Object();
  protected final List<Predicate<Snapshot>> snapshotListeners = new ArrayList<>();
  protected final List<Predicate<DecodedTrace>> traceListeners = new ArrayList<>();
  protected final List<Predicate<ProbeStatus>> probeStatusListeners = new ArrayList<>();

  @BeforeAll
  static void setupAll() throws Exception {
    Files.createDirectories(LOG_FILE_BASE);
  }

  @BeforeEach
  void setup(TestInfo testInfo) throws Exception {
    datadogAgentServer = new MockWebServer();
    probeMockDispatcher = new MockDispatcher();
    probeMockDispatcher.setDispatcher(this::datadogAgentDispatch);
    datadogAgentServer.setDispatcher(probeMockDispatcher);
    probeUrl = datadogAgentServer.url(CONFIG_URL_PATH);
    LOG.info("DatadogAgentServer on {}", datadogAgentServer.getPort());
    snapshotUrl = datadogAgentServer.url(SNAPSHOT_URL_PATH);
    statsDServer = new StatsDServer();
    statsDServer.start();
    LOG.info("statsDServer on {}", statsDServer.getPort());
    logFilePath = LOG_FILE_BASE.resolve(testInfo.getDisplayName() + ".log");
  }

  @AfterEach
  void teardown() throws Exception {
    if (targetProcess != null) {
      targetProcess.destroyForcibly();
    }
    datadogAgentServer.shutdown();
    statsDServer.close();
  }

  protected ProcessBuilder createProcessBuilder(Path logFilePath, String... params) {
    List<String> commandParams = getDebuggerCommandParams();
    commandParams.add("-Ddd.trace.enabled=false");
    return ProcessBuilderHelper.createProcessBuilder(
        commandParams, logFilePath, getAppClass(), params);
  }

  protected abstract String getAppClass();

  protected abstract String getAppId();

  protected List<String> getDebuggerCommandParams() {
    return new ArrayList<>(
        Arrays.asList(
            "-Ddd.service.name=" + getAppId(),
            "-Ddd.profiling.enabled=false",
            "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=debug",
            "-Dorg.slf4j.simpleLogger.defaultLogLevel=info",
            "-Ddd.jmxfetch.start-delay=0",
            "-Ddd.jmxfetch.enabled=false",
            "-Ddd.dynamic.instrumentation.enabled=true",
            "-Ddd.remote_config.poll_interval.seconds=1",
            "-Ddd.trace.agent.url=http://localhost:" + datadogAgentServer.getPort(),
            "-Ddd.jmxfetch.statsd.port=" + statsDServer.getPort(),
            "-Ddd.dynamic.instrumentation.classfile.dump.enabled=true",
            // to verify each snapshot upload one by one
            "-Ddd.dynamic.instrumentation.upload.batch.size=1",
            // flush uploads every 100ms to have quick tests
            "-Ddd.dynamic.instrumentation.upload.flush.interval=100"));
  }

  protected RecordedRequest retrieveSnapshotRequest() throws Exception {
    return retrieveRequest(
        request -> {
          try {
            return request.getPath().startsWith(SNAPSHOT_URL_PATH)
                && request.getBody().indexOf(ByteString.encodeUtf8("diagnostics")) == -1;
          } catch (IOException ex) {
            throw new RuntimeException(ex);
          }
        });
  }

  protected DecodedSpan retrieveSpanRequest(String name) throws Exception {
    DecodedSpan decodedSpan = null;
    int attempt = 3;
    retrieveSpan:
    do {
      System.out.println("retrieveSpanRequest...");
      RecordedRequest spanRequest =
          retrieveRequest(request -> request.getPath().equals(TRACE_URL_PATH));
      attempt--;
      if (spanRequest == null) {
        continue;
      }
      DecodedMessage decodedMessage = Decoder.decodeV04(spanRequest.getBody().readByteArray());
      System.out.println(
          "Traces="
              + decodedMessage.getTraces().size()
              + " Spans="
              + decodedMessage.getTraces().get(0).getSpans().size());
      for (int traceIdx = 0; traceIdx < decodedMessage.getTraces().size(); traceIdx++) {
        List<DecodedSpan> spans = decodedMessage.getTraces().get(traceIdx).getSpans();
        for (int spanIdx = 0; spanIdx < spans.size(); spanIdx++) {
          decodedSpan = spans.get(spanIdx);
          System.out.printf(
              "Trace[%d].Span[%d] name=%s resource=%s Meta=%s%n",
              traceIdx,
              spanIdx,
              decodedSpan.getName(),
              decodedSpan.getResource(),
              decodedSpan.getMeta());
          if (decodedSpan.getName().equals(name)) {
            break retrieveSpan;
          }
        }
      }
    } while (attempt > 0);
    return decodedSpan;
  }

  protected enum RequestType {
    SNAPSHOT {
      @Override
      public boolean process(BaseIntegrationTest baseIntegrationTest, RecordedRequest request) {
        if (!request.getPath().startsWith(SNAPSHOT_URL_PATH)) {
          return false;
        }
        try {
          if (request.getBody().indexOf(DIAGNOSTICS_STR) > -1) {
            return false;
          }
        } catch (IOException ex) {
          ex.printStackTrace();
        }
        String bodyStr = request.getBody().readUtf8();
        LOG.info("got snapshot: {}", bodyStr);
        JsonAdapter<List<JsonSnapshotSerializer.IntakeRequest>> adapter =
            createAdapterForSnapshot();
        try {
          List<JsonSnapshotSerializer.IntakeRequest> intakeRequests = adapter.fromJson(bodyStr);
          for (JsonSnapshotSerializer.IntakeRequest intakeRequest : intakeRequests) {
            Snapshot snapshot = intakeRequest.getDebugger().getSnapshot();
            for (Predicate<Snapshot> listener : baseIntegrationTest.snapshotListeners) {
              if (listener.test(snapshot)) {
                return true;
              }
            }
          }
        } catch (IOException ex) {
          ex.printStackTrace();
        }
        return false;
      }
    },
    SPAN {
      @Override
      public boolean process(BaseIntegrationTest baseIntegrationTest, RecordedRequest request) {
        if (!request.getPath().equals(TRACE_URL_PATH)) {
          return false;
        }
        DecodedMessage decodedMessage = Decoder.decodeV04(request.getBody().readByteArray());
        LOG.info("got traces: {}", decodedMessage.getTraces().size());
        for (Predicate<DecodedTrace> listener : baseIntegrationTest.traceListeners) {
          for (DecodedTrace trace : decodedMessage.getTraces()) {
            if (listener.test(trace)) {
              return true;
            }
          }
        }
        return false;
      }
    },
    PROBE_STATUS {
      @Override
      public boolean process(BaseIntegrationTest baseIntegrationTest, RecordedRequest request) {
        if (!request.getPath().startsWith(SNAPSHOT_URL_PATH)) {
          return false;
        }
        try {
          if (request.getBody().indexOf(DIAGNOSTICS_STR) == -1) {
            return false;
          }
        } catch (IOException ex) {
          ex.printStackTrace();
        }
        JsonAdapter<List<ProbeStatus>> adapter =
            MoshiHelper.createMoshiProbeStatus()
                .adapter(Types.newParameterizedType(List.class, ProbeStatus.class));
        String bodyStr = request.getBody().readUtf8();
        LOG.info("got probe status: {}", bodyStr);
        try {
          List<ProbeStatus> probeStatuses = adapter.fromJson(bodyStr);
          for (Predicate<ProbeStatus> listener : baseIntegrationTest.probeStatusListeners) {
            for (ProbeStatus probeStatus : probeStatuses) {
              if (listener.test(probeStatus)) {
                return true;
              }
            }
          }
        } catch (IOException ex) {
          ex.printStackTrace();
        }
        return false;
      }
    };

    public abstract boolean process(
        BaseIntegrationTest baseIntegrationTest, RecordedRequest request);
  }

  protected void registerSnapshotListener(Predicate<Snapshot> listener) {
    snapshotListeners.add(listener);
  }

  protected void registerTraceListener(Predicate<DecodedTrace> listener) {
    traceListeners.add(listener);
  }

  protected void registerProbeStatusListener(Predicate<ProbeStatus> listener) {
    probeStatusListeners.add(listener);
  }

  protected void processRequests() throws InterruptedException {
    RecordedRequest request;
    do {
      request = datadogAgentServer.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS);
      if (request == null) {
        throw new RuntimeException("timeout!");
      }
      System.out.println("processRequests path=" + request.getPath());
      for (RequestType requestType : RequestType.values()) {
        if (requestType.process(this, request)) {
          return;
        }
      }
    } while (request != null);
  }

  protected RecordedRequest retrieveRequest(Predicate<RecordedRequest> filterRequest)
      throws InterruptedException {
    long ts = System.nanoTime();
    RecordedRequest request;
    do {
      request = datadogAgentServer.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS);
      if (request == null) {
        System.out.println("retreiveRequest request==null => timeout 10 sec");
      } else {
        System.out.println(
            "retreiveRequest request!=null path=" + request.getPath() + " testing...");
      }
    } while (request != null && !filterRequest.test(request));
    long dur = System.nanoTime() - ts;
    LOG.info(
        "request retrieved in {} seconds", TimeUnit.SECONDS.convert(dur, TimeUnit.NANOSECONDS));
    return request;
  }

  protected ProbeStatus retrieveProbeStatusRequest() throws Exception {
    RecordedRequest request;
    do {
      request = datadogAgentServer.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS);
    } while (request != null && !request.getPath().startsWith(SNAPSHOT_URL_PATH));
    assertNotNull(request);
    JsonAdapter<List<ProbeStatus>> adapter =
        MoshiHelper.createMoshiProbeStatus()
            .adapter(Types.newParameterizedType(List.class, ProbeStatus.class));
    String bodyStr = request.getBody().readUtf8();
    LOG.info("got snapshot: {}", bodyStr);
    List<ProbeStatus> probeStatuses = adapter.fromJson(bodyStr);
    assertEquals(1, probeStatuses.size());
    return probeStatuses.get(0);
  }

  protected String retrieveStatsdMessage(String str) {
    return statsDServer.waitForMessage(str);
  }

  private MockResponse datadogAgentDispatch(RecordedRequest request) {
    LOG.info("datadogAgentDispatch request path: {}", request.getPath());
    if (request.getPath().equals("/info")) {
      return AGENT_INFO_RESPONSE;
    }
    if (request.getPath().equals("/telemetry/proxy/api/v2/apmtelemetry")) {
      // Ack every telemetry request. This is needed if telemetry is enabled in the tests.
      return TELEMETRY_RESPONSE;
    }
    if (request.getPath().startsWith(SNAPSHOT_URL_PATH)) {
      return new MockResponse().setResponseCode(200);
    }
    if (request.getPath().equals("/v0.7/config")) {
      return handleConfigRequests();
    }
    return EMPTY_200_RESPONSE;
  }

  private MockResponse handleConfigRequests() {
    Configuration configuration;
    synchronized (configLock) {
      configuration = getCurrentConfiguration();
      configProvided = true;
      configLock.notifyAll();
    }
    if (configuration == null) {
      configuration = createConfig(Collections.emptyList());
    }
    try {
      JsonAdapter<Configuration> adapter =
          MoshiConfigTestHelper.createMoshiConfig().adapter(Configuration.class);
      String json = adapter.toJson(configuration);
      System.out.println("Sending json config: " + json);
      String remoteConfigJson = RemoteConfigHelper.encode(json, UUID.randomUUID().toString());
      return new MockResponse().setResponseCode(200).setBody(remoteConfigJson);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private Configuration getCurrentConfiguration() {
    synchronized (configLock) {
      return currentConfiguration;
    }
  }

  protected boolean isConfigProvided() {
    synchronized (configLock) {
      return configProvided;
    }
  }

  protected static JsonAdapter<List<JsonSnapshotSerializer.IntakeRequest>>
      createAdapterForSnapshot() {
    return MoshiSnapshotTestHelper.createMoshiSnapshot()
        .adapter(
            Types.newParameterizedType(List.class, JsonSnapshotSerializer.IntakeRequest.class));
  }

  protected void setCurrentConfiguration(Configuration configuration) {
    synchronized (configLock) {
      this.currentConfiguration = configuration;
      configProvided = false;
    }
  }

  protected Configuration createConfig(LogProbe logProbe) {
    return createConfig(Arrays.asList(logProbe));
  }

  protected Configuration createMetricConfig(MetricProbe metricProbe) {
    return Configuration.builder()
        .setService(getAppId())
        .addMetricProbes(Collections.singletonList(metricProbe))
        .build();
  }

  protected Configuration createSpanDecoConfig(SpanDecorationProbe spanDecorationProbe) {
    return Configuration.builder()
        .setService(getAppId())
        .addSpanDecorationProbes(Collections.singletonList(spanDecorationProbe))
        .build();
  }

  protected Configuration createSpanConfig(SpanProbe spanProbe) {
    return Configuration.builder()
        .setService(getAppId())
        .addSpanProbes(Collections.singletonList(spanProbe))
        .build();
  }

  protected Configuration createConfig(Collection<LogProbe> logProbes) {
    return new Configuration(getAppId(), logProbes);
  }

  protected Configuration createConfig(
      Collection<LogProbe> logProbes,
      Configuration.FilterList allowList,
      Configuration.FilterList denyList) {
    return Configuration.builder()
        .setService(getAppId())
        .addLogProbes(logProbes)
        .addAllowList(allowList)
        .addDenyList(denyList)
        .build();
  }

  protected void assertCaptureArgs(
      CapturedContext context, String name, String typeName, String value) {
    CapturedContext.CapturedValue capturedValue = context.getArguments().get(name);
    assertEquals(typeName, capturedValue.getType());
    Object objValue = capturedValue.getValue();
    if (objValue.getClass().isArray()) {
      assertEquals(value, Arrays.toString((Object[]) objValue));
    } else {
      assertEquals(value, String.valueOf(objValue));
    }
  }

  protected void assertCaptureLocals(
      CapturedContext context, String name, String typeName, String value) {
    CapturedContext.CapturedValue localVar = context.getLocals().get(name);
    assertEquals(typeName, localVar.getType());
    assertEquals(value, localVar.getValue());
  }

  protected void assertCaptureReturnValue(CapturedContext context, String typeName, String value) {
    CapturedContext.CapturedValue returnValue = context.getLocals().get("@return");
    assertEquals(typeName, returnValue.getType());
    assertEquals(value, returnValue.getValue());
  }

  protected void assertCaptureThrowable(
      CapturedContext.CapturedThrowable throwable, String typeName, String message) {
    assertNotNull(throwable);
    assertEquals(typeName, throwable.getType());
    assertEquals(message, throwable.getMessage());
  }

  protected static boolean logHasErrors(Path logFilePath, Function<String, Boolean> checker)
      throws IOException {
    long errorLines =
        Files.lines(logFilePath)
            .filter(
                it ->
                    it.contains(" ERROR ")
                        || it.contains("ASSERTION FAILED")
                        || it.contains("Error:")
                        || checker.apply(it))
            .peek(System.out::println)
            .count();
    boolean hasErrors = errorLines > 0;
    if (hasErrors) {
      System.out.println(
          "Test application log is containing errors. See full run logs in " + logFilePath);
    }
    return hasErrors;
  }

  private static class MockDispatcher extends okhttp3.mockwebserver.QueueDispatcher {
    private Function<RecordedRequest, MockResponse> dispatcher;

    @Override
    public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
      return dispatcher.apply(request);
    }

    public void setDispatcher(Function<RecordedRequest, MockResponse> dispatcher) {
      this.dispatcher = dispatcher;
    }
  }

  private static class StatsDServer extends Thread {
    private final DatagramSocket socket;
    private final BlockingQueue<String> msgQueue = new ArrayBlockingQueue<>(32);
    private volatile String lastMessage;
    private volatile boolean running = true;

    StatsDServer() throws SocketException {
      socket = new DatagramSocket();
    }

    @Override
    public void run() {
      byte[] buf = new byte[1024];
      DatagramPacket packet = new DatagramPacket(buf, buf.length);
      while (running) {
        try {
          socket.receive(packet);
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
        lastMessage = new String(packet.getData(), 0, packet.getLength());
        System.out.println("received statsd: " + lastMessage);
        try {
          msgQueue.offer(lastMessage, 30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      }
      socket.close();
    }

    String lastMessage() {
      return lastMessage;
    }

    String waitForMessage(String str) {
      String msg;
      do {
        try {
          msg = msgQueue.poll(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          throw new RuntimeException(e);
        }
      } while (msg != null && !msg.contains(str));
      return msg;
    }

    void close() {
      running = false;
    }

    int getPort() {
      return socket.getLocalPort();
    }
  }
}
