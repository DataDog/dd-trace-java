package datadog.smoketest;

import static datadog.smoketest.ProcessBuilderHelper.buildDirectory;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

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
import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.Types;
import datadog.trace.bootstrap.debugger.CapturedContext;
import datadog.trace.bootstrap.debugger.ProbeRateLimiter;
import datadog.trace.test.agent.decoder.DecodedMessage;
import datadog.trace.test.agent.decoder.DecodedTrace;
import datadog.trace.test.agent.decoder.Decoder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import okhttp3.Headers;
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
  protected static final String LOG_UPLOAD_URL_PATH = "/debugger/v1/input";
  protected static final String SNAPSHOT_UPLOAD_URL_PATH = "/debugger/v2/input";
  protected static final String DIAGNOSTICS_URL_PATH = "/debugger/v1/diagnostics";
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
          + LOG_UPLOAD_URL_PATH
          + "\", \""
          + DIAGNOSTICS_URL_PATH
          + "\", \""
          + CONFIG_URL_PATH
          + "\"]}";
  private static final MockResponse AGENT_INFO_RESPONSE =
      new MockResponse().setResponseCode(200).setBody(INFO_CONTENT);
  private static final MockResponse TELEMETRY_RESPONSE = new MockResponse().setResponseCode(202);
  protected static final MockResponse EMPTY_200_RESPONSE = new MockResponse().setResponseCode(200);

  private static final ByteString DIAGNOSTICS_STR = ByteString.encodeUtf8("{\"diagnostics\":");
  private static final String LD_CONFIG_ID = UUID.randomUUID().toString();
  private static final String APM_CONFIG_ID = UUID.randomUUID().toString();
  public static final String LIVE_DEBUGGING_PRODUCT = "LIVE_DEBUGGING";
  public static final String APM_TRACING_PRODUCT = "APM_TRACING";

  protected MockWebServer datadogAgentServer;
  private MockDispatcher probeMockDispatcher;
  private StatsDServer statsDServer;
  private HttpUrl probeUrl;
  private HttpUrl snapshotUrl;
  protected Path logFilePath;
  protected Process targetProcess;
  private Configuration currentConfiguration;
  private ConfigOverrides configOverrides;
  private boolean configProvided;
  protected final Object configLock = new Object();
  protected final List<Consumer<JsonSnapshotSerializer.IntakeRequest>> intakeRequestListeners =
      new ArrayList<>();
  protected final List<Consumer<Snapshot>> snapshotListeners = new ArrayList<>();
  protected final List<Consumer<DecodedTrace>> traceListeners = new ArrayList<>();
  protected final List<Consumer<ProbeStatus>> probeStatusListeners = new ArrayList<>();
  protected int batchSize = 1; // to verify each snapshot upload one by one

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
    snapshotUrl = datadogAgentServer.url(LOG_UPLOAD_URL_PATH);
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
    ProbeRateLimiter.resetAll();
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
            "-Ddatadog.slf4j.simpleLogger.defaultLogLevel=info",
            "-Ddatadog.slf4j.simpleLogger.log.com.datadog.debugger=debug",
            "-Ddatadog.slf4j.simpleLogger.log.datadog.remoteconfig=debug",
            "-Ddd.jmxfetch.start-delay=0",
            "-Ddd.jmxfetch.enabled=false",
            "-Ddd.dynamic.instrumentation.enabled=true",
            "-Ddd.remote_config.poll_interval.seconds=1",
            "-Ddd.trace.agent.url=http://localhost:" + datadogAgentServer.getPort(),
            "-Ddd.jmxfetch.statsd.port=" + statsDServer.getPort(),
            "-Ddd.dynamic.instrumentation.classfile.dump.enabled=true",
            "-Ddd.dynamic.instrumentation.upload.batch.size=" + batchSize,
            // flush uploads every 100ms to have quick tests
            "-Ddd.dynamic.instrumentation.upload.flush.interval=100",
            // increase timeout for serialization
            "-Ddd.dynamic.instrumentation.capture.timeout=200"));
  }

  protected enum RequestType {
    SNAPSHOT {
      @Override
      public void process(BaseIntegrationTest baseIntegrationTest, RecordedRequest request) {
        if (!(request.getPath().startsWith(LOG_UPLOAD_URL_PATH)
            || request.getPath().startsWith(DIAGNOSTICS_URL_PATH)
            || request.getPath().startsWith(SNAPSHOT_UPLOAD_URL_PATH))) {
          return;
        }
        try {
          if (request.getBody().indexOf(DIAGNOSTICS_STR) > -1) {
            return;
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
            for (Consumer<JsonSnapshotSerializer.IntakeRequest> listener :
                baseIntegrationTest.intakeRequestListeners) {
              listener.accept(intakeRequest);
            }
            Snapshot snapshot = intakeRequest.getDebugger().getSnapshot();
            for (Consumer<Snapshot> listener : baseIntegrationTest.snapshotListeners) {
              listener.accept(snapshot);
            }
          }
        } catch (IOException ex) {
          ex.printStackTrace();
        }
      }
    },
    SPAN {
      @Override
      public void process(BaseIntegrationTest baseIntegrationTest, RecordedRequest request) {
        if (!request.getPath().equals(TRACE_URL_PATH)) {
          return;
        }
        DecodedMessage decodedMessage = Decoder.decodeV04(request.getBody().readByteArray());
        LOG.info("got traces: {}", decodedMessage.getTraces().size());
        for (Consumer<DecodedTrace> listener : baseIntegrationTest.traceListeners) {
          for (DecodedTrace trace : decodedMessage.getTraces()) {
            listener.accept(trace);
          }
        }
      }
    },
    PROBE_STATUS {
      @Override
      public void process(BaseIntegrationTest baseIntegrationTest, RecordedRequest request) {
        if (!request.getPath().startsWith(DIAGNOSTICS_URL_PATH)) {
          return;
        }
        try {
          if (request.getBody().indexOf(DIAGNOSTICS_STR) == -1) {
            return;
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
          // Http multipart decode
          String partBody = parseMultiPart(request, bodyStr);
          if (partBody == null) {
            return;
          }
          List<ProbeStatus> probeStatuses = adapter.fromJson(partBody);
          for (Consumer<ProbeStatus> listener : baseIntegrationTest.probeStatusListeners) {
            for (ProbeStatus probeStatus : probeStatuses) {
              listener.accept(probeStatus);
            }
          }
        } catch (IOException ex) {
          ex.printStackTrace();
        }
      }
    };

    public abstract void process(BaseIntegrationTest baseIntegrationTest, RecordedRequest request);
  }

  private static String parseMultiPart(RecordedRequest request, String bodyStr) throws IOException {
    // To parse it as multipart, you'll need to use the boundary from the Content-Type header
    String contentType = request.getHeader("Content-Type");
    String boundary = null;
    // Extract the boundary from the Content-Type header
    if (contentType != null && contentType.startsWith("multipart/")) {
      String[] parts = contentType.split("boundary=");
      if (parts.length > 1) {
        boundary = parts[1].trim();
      }
    }

    try (BufferedReader reader = new BufferedReader(new StringReader(bodyStr))) {
      String line;
      while ((line = reader.readLine()) != null) {
        // Process each line of the multipart body
        if (line.startsWith("--" + boundary)) {
          // This is a part boundary
          // The next line should be headers for this part
          Headers.Builder partHeaders = new Headers.Builder();
          while (!(line = reader.readLine()).isEmpty()) {
            // Parse headers
            int colon = line.indexOf(':');
            if (colon != -1) {
              partHeaders.add(line.substring(0, colon).trim(), line.substring(colon + 1).trim());
            }
          }

          // Now read the part content until we hit the boundary
          StringBuilder partContent = new StringBuilder();
          while ((line = reader.readLine()) != null && !line.startsWith("--" + boundary)) {
            partContent.append(line).append("\n");
          }
          return partContent.toString().trim();
        }
      }
    }
    return null;
  }

  protected void registerIntakeRequestListener(
      Consumer<JsonSnapshotSerializer.IntakeRequest> listener) {
    intakeRequestListeners.add(listener);
  }

  protected void registerSnapshotListener(Consumer<Snapshot> listener) {
    snapshotListeners.add(listener);
  }

  protected void registerTraceListener(Consumer<DecodedTrace> listener) {
    traceListeners.add(listener);
  }

  protected void resetTraceListener() {
    traceListeners.clear();
  }

  protected void registerProbeStatusListener(Consumer<ProbeStatus> listener) {
    probeStatusListeners.add(listener);
  }

  protected AtomicBoolean registerCheckReceivedInstalledEmitting() {
    AtomicBoolean received = new AtomicBoolean();
    AtomicBoolean installed = new AtomicBoolean();
    AtomicBoolean emitting = new AtomicBoolean();
    AtomicBoolean result = new AtomicBoolean();
    registerProbeStatusListener(
        probeStatus -> {
          if (probeStatus.getDiagnostics().getStatus() == ProbeStatus.Status.RECEIVED) {
            received.set(true);
          }
          if (probeStatus.getDiagnostics().getStatus() == ProbeStatus.Status.INSTALLED) {
            installed.set(true);
          }
          if (probeStatus.getDiagnostics().getStatus() == ProbeStatus.Status.EMITTING) {
            emitting.set(true);
          }
          result.set(received.get() && installed.get() && emitting.get());
        });
    return result;
  }

  protected void processRequests(BooleanSupplier conditionOfDone) throws InterruptedException {
    long start = System.currentTimeMillis();
    try {
      RecordedRequest request;
      do {
        request = datadogAgentServer.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS);
        if (request == null) {
          throw new RuntimeException("timeout!");
        }
        LOG.info("processRequests path={}", request.getPath());
        for (RequestType requestType : RequestType.values()) {
          requestType.process(this, request);
          if (conditionOfDone.getAsBoolean()) {
            return;
          }
        }
      } while (request != null && (System.currentTimeMillis() - start < 30_000));
      throw new RuntimeException("timeout!");
    } finally {
      probeStatusListeners.clear();
    }
  }

  protected void processRemainingRequests() throws InterruptedException {
    RecordedRequest request;
    do {
      request = datadogAgentServer.takeRequest(1, TimeUnit.MILLISECONDS);
      if (request == null) {
        break;
      }
      LOG.info(
          "processRemainingRequests path={} body={}",
          request.getPath(),
          request.getBody().readUtf8());
    } while (request != null);
  }

  protected RecordedRequest retrieveRequest(Predicate<RecordedRequest> filterRequest)
      throws InterruptedException {
    long ts = System.nanoTime();
    RecordedRequest request;
    do {
      request = datadogAgentServer.takeRequest(REQUEST_WAIT_TIMEOUT, TimeUnit.SECONDS);
      if (request == null) {
        LOG.info("retreiveRequest request==null => timeout 10 sec");
      } else {
        LOG.info("retreiveRequest request!=null path={} testing...", request.getPath());
      }
    } while (request != null && !filterRequest.test(request));
    long dur = System.nanoTime() - ts;
    LOG.info(
        "request retrieved in {} seconds", TimeUnit.SECONDS.convert(dur, TimeUnit.NANOSECONDS));
    return request;
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
    if (request.getPath().startsWith(LOG_UPLOAD_URL_PATH)) {
      return EMPTY_200_RESPONSE;
    }
    if (request.getPath().equals("/v0.7/config")) {
      return handleConfigRequests();
    }
    return EMPTY_200_RESPONSE;
  }

  protected MockResponse handleConfigRequests() {
    Configuration configuration;
    ConfigOverrides configOverrides;
    synchronized (configLock) {
      configuration = getCurrentConfiguration();
      configOverrides = getConfigOverrides();
      configProvided = true;
      configLock.notifyAll();
    }
    if (configuration == null) {
      configuration = createConfig(Collections.emptyList());
    }
    try {
      JsonAdapter<Configuration> adapter =
          MoshiConfigTestHelper.createMoshiConfig().adapter(Configuration.class);
      String liveDebuggingJson = adapter.toJson(configuration);
      LOG.info("Sending Live Debugging json: {}", liveDebuggingJson);
      List<RemoteConfigHelper.RemoteConfig> remoteConfigs = new ArrayList<>();
      remoteConfigs.add(
          new RemoteConfigHelper.RemoteConfig(
              LIVE_DEBUGGING_PRODUCT, liveDebuggingJson, LD_CONFIG_ID));
      if (configOverrides != null) {
        JsonAdapter<ConfigOverrides> configAdapter =
            new Moshi.Builder().build().adapter(ConfigOverrides.class);
        String configOverridesJson = configAdapter.toJson(configOverrides);
        LOG.info("Sending configOverrides json: {}", configOverridesJson);
        remoteConfigs.add(
            new RemoteConfigHelper.RemoteConfig(
                APM_TRACING_PRODUCT, configOverridesJson, APM_CONFIG_ID));
      }
      String remoteConfigJson = RemoteConfigHelper.encode(remoteConfigs);
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

  private ConfigOverrides getConfigOverrides() {
    synchronized (configLock) {
      return configOverrides;
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

  protected void setConfigOverrides(ConfigOverrides configOverrides) {
    synchronized (configLock) {
      this.configOverrides = configOverrides;
    }
  }

  protected Configuration createConfig(LogProbe logProbe) {
    return createConfig(Arrays.asList(logProbe));
  }

  protected Configuration createMetricConfig(MetricProbe metricProbe) {
    return Configuration.builder().setService(getAppId()).add(metricProbe).build();
  }

  protected Configuration createSpanDecoConfig(SpanDecorationProbe spanDecorationProbe) {
    return Configuration.builder().setService(getAppId()).add(spanDecorationProbe).build();
  }

  protected Configuration createSpanConfig(SpanProbe spanProbe) {
    return Configuration.builder().setService(getAppId()).add(spanProbe).build();
  }

  protected Configuration createConfig(List<LogProbe> logProbes) {
    return new Configuration(getAppId(), logProbes);
  }

  protected Configuration createConfig(
      Collection<LogProbe> logProbes,
      Configuration.FilterList allowList,
      Configuration.FilterList denyList) {
    return Configuration.builder()
        .setService(getAppId())
        .add(logProbes)
        .addAllowList(allowList)
        .addDenyList(denyList)
        .build();
  }

  protected void assertCaptureArgs(
      CapturedContext context, String name, String typeName, String value) {
    CapturedContext.CapturedValue capturedValue = context.getArguments().get(name);
    assertEquals(typeName, capturedValue.getType());
    Object objValue = capturedValue.getValue();
    assertNotNull(
        objValue, "objValue null for argName=" + name + " capturedValue=" + capturedValue);
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

  protected static boolean logHasErrors(Path logFilePath, Function<String, Boolean> checker) {
    try {
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
        LOG.info("Test application log is containing errors. See full run logs in {}", logFilePath);
      }
      return hasErrors;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
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
        LOG.info("received statsd: {}", lastMessage);
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

  static final class ConfigOverrides {
    @Json(name = "lib_config")
    public LibConfig libConfig;
  }

  static final class LibConfig {
    @Json(name = "dynamic_instrumentation_enabled")
    public Boolean dynamicInstrumentationEnabled;

    @Json(name = "exception_replay_enabled")
    public Boolean exceptionReplayEnabled;

    @Json(name = "code_origin_enabled")
    public Boolean codeOriginEnabled;

    @Json(name = "live_debugging_enabled")
    public Boolean liveDebuggingEnabled;
  }
}
