package datadog.smoketest;

import static datadog.environment.JavaVirtualMachine.isJ9;
import static datadog.trace.api.ProtocolVersion.V0_4;
import static datadog.trace.api.ProtocolVersion.V0_5;
import static datadog.trace.api.ProtocolVersion.V1_0;
import static datadog.trace.test.util.ForkedTestUtils.getMaxMemoryArgumentForFork;
import static datadog.trace.test.util.ForkedTestUtils.getMinMemoryArgumentForFork;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.time.Duration.ofSeconds;
import static java.util.concurrent.TimeUnit.SECONDS;
import static java.util.stream.Collectors.joining;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.environment.OperatingSystem;
import datadog.trace.agent.test.server.http.JavaTestHttpServer;
import datadog.trace.agent.test.server.http.JavaTestHttpServer.HandlerApi.RequestApi;
import datadog.trace.agent.test.server.http.JavaTestHttpServer.HandlerApi.ResponseApi;
import datadog.trace.agent.test.utils.PortUtils;
import datadog.trace.api.ProtocolVersion;
import datadog.trace.test.agent.decoder.DecodedMessage;
import datadog.trace.test.agent.decoder.DecodedTrace;
import datadog.trace.test.agent.decoder.Decoder;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;

/**
 * Java-only smoke-test base for tests that launch one instrumented process and assert decoded
 * traces.
 *
 * <p>This temporarily duplicates the relevant behavior from the Groovy smoke-test hierarchy so
 * individual smoke tests can migrate to JUnit 5 without requiring a repository-wide migration.
 */
public abstract class AbstractJavaSmokeTest {
  private static final int PROFILING_START_DELAY_SECONDS = 1;
  private static final int PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS = 5;
  private static final int TRACE_WAIT_TIMEOUT_SECONDS = 30;

  private static final String SERVICE_NAME = "smoke-test-java-app";
  private static final String ENV = "smoketest";
  private static final String VERSION = "99";
  private static final String API_KEY = "01234567890abcdef123456789ABCDEF";
  private static final String AGENT_INFO_RESPONSE =
      readResource(AbstractJavaSmokeTest.class, "agent-info-response.json");

  private static final List<String> NOISY_ENVIRONMENT_VARIABLES =
      Arrays.asList("CI_COMMIT_TITLE", "CI_COMMIT_MESSAGE", "CI_COMMIT_DESCRIPTION");

  private static final DateTimeFormatter LOG_FILE_TIMESTAMP_FORMATTER =
      DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss.SSS", Locale.ROOT).withZone(ZoneOffset.UTC);

  protected final AtomicInteger traceCount = new AtomicInteger();

  private final CopyOnWriteArrayList<DecodedTrace> decodedTraces = new CopyOnWriteArrayList<>();

  protected Path buildDirectory;
  private String agentShadowJarPath;
  protected List<String> defaultJavaProperties;
  private OutputThreads outputThreads;
  private JavaTestHttpServer server;
  protected Process testedProcess;
  private Path logFilePath;
  private volatile Throwable traceDecodingFailure;

  @BeforeEach
  protected void startSmokeTest() throws Exception {
    buildDirectory = requiredDirectory("datadog.smoketest.builddir");
    agentShadowJarPath = requiredFile("datadog.smoketest.agent.shadowJar.path").toString();
    traceCount.set(0);
    decodedTraces.clear();
    traceDecodingFailure = null;
    outputThreads = new OutputThreads();
    server = createServer();
    defaultJavaProperties = createDefaultJavaProperties();

    String timestamp = LOG_FILE_TIMESTAMP_FORMATTER.format(Instant.now());
    logFilePath =
        buildDirectory
            .resolve("reports")
            .resolve("testProcess." + getClass().getName() + "." + timestamp + ".0.log");
    Files.createDirectories(logFilePath.getParent());

    ProcessBuilder processBuilder = createProcessBuilder();
    Map<String, String> environment = processBuilder.environment();
    environment.put("JAVA_HOME", System.getProperty("java.home"));
    environment.put("DD_API_KEY", API_KEY);
    environment.keySet().removeAll(NOISY_ENVIRONMENT_VARIABLES);
    processBuilder.redirectErrorStream(true);

    testedProcess = processBuilder.start();
    outputThreads.captureOutput(testedProcess, logFilePath.toFile());
    if (!testedProcess.isAlive()) {
      assertEquals(
          0, testedProcess.exitValue(), "Process exited abnormally; log file: " + logFilePath);
    }
  }

  @AfterEach
  protected void stopSmokeTest() throws Exception {
    Throwable failure = null;
    failure = runCleanup(failure, this::stopProcess);
    if (outputThreads != null) {
      failure = runCleanup(failure, outputThreads::close);
    }
    failure = runCleanup(failure, this::assertNoErrorLogs);
    if (server != null) {
      failure = runCleanup(failure, server::close);
    }
    if (traceDecodingFailure != null) {
      failure = addFailure(failure, traceDecodingFailure);
    }
    if (failure != null) {
      rethrow(failure);
    }
  }

  protected abstract ProcessBuilder createProcessBuilder();

  protected final void waitForTrace(Predicate<DecodedTrace> predicate) {
    await("matching trace")
        .atMost(ofSeconds(TRACE_WAIT_TIMEOUT_SECONDS))
        .until(
            () -> {
              throwIfTraceDecodingFailed();
              return decodedTraces.stream().anyMatch(predicate);
            });
  }

  private JavaTestHttpServer createServer() {
    return JavaTestHttpServer.httpServer(
        configuredServer ->
            configuredServer.handlers(
                handlers -> {
                  handlers.prefix(
                      "/info", api -> api.getResponse().status(200).send(AGENT_INFO_RESPONSE));
                  handlers.prefix(
                      "/v0.4/traces",
                      api -> handleTrace(V0_4, api.getRequest(), api.getResponse()));
                  handlers.prefix(
                      "/v0.5/traces",
                      api -> handleTrace(V0_5, api.getRequest(), api.getResponse()));
                  handlers.prefix(
                      "/v1.0/traces",
                      api -> handleTrace(V1_0, api.getRequest(), api.getResponse()));
                  handlers.prefix("/v0.6/stats", api -> api.getResponse().status(200).send());
                  handlers.prefix("/v0.7/config", api -> api.getResponse().status(200).send("{}"));
                  handlers.prefix(
                      "/telemetry/proxy/api/v2/apmtelemetry",
                      api -> api.getResponse().status(202).send());
                  handlers.prefix("/evp_proxy/v2/", api -> api.getResponse().status(200).send());
                }));
  }

  private void handleTrace(ProtocolVersion protocol, RequestApi request, ResponseApi response) {
    String countHeader = request.getHeader("X-Datadog-Trace-Count");
    int expectedTraceCount = countHeader == null ? 0 : Integer.parseInt(countHeader);
    byte[] body = request.getBody();
    if (body.length > 0) {
      try {
        DecodedMessage message;
        switch (protocol) {
          case V0_4:
            message = Decoder.decodeV04(body);
            break;
          case V0_5:
            message = Decoder.decodeV05(body);
            break;
          case V1_0:
            message = Decoder.decodeV1(body);
            break;
          default:
            throw new IllegalArgumentException("Unsupported trace protocol: " + protocol);
        }
        assertEquals(expectedTraceCount, message.getTraces().size());
        decodedTraces.addAll(message.getTraces());
      } catch (RuntimeException | Error failure) {
        traceDecodingFailure = failure;
        throw failure;
      }
    }
    traceCount.addAndGet(expectedTraceCount);
    System.out.println("Received " + protocol + " traces: " + countHeader);
    response.status(200).send();
  }

  private List<String> createDefaultJavaProperties() {
    String temporaryDirectory = "/tmp";
    String preferencesDirectory =
        temporaryDirectory + "/userPrefs/" + getClass().getSimpleName() + "_" + System.nanoTime();
    boolean isIbmJvm = System.getProperty("java.vendor", "").contains("IBM");
    boolean isDdprofSafe = !isJ9();

    List<String> properties = new ArrayList<>();
    properties.add(getMaxMemoryArgumentForFork());
    properties.add(getMinMemoryArgumentForFork());
    properties.add("-javaagent:" + agentShadowJarPath);
    properties.add(
        isIbmJvm
            ? "-Xdump:directory=" + temporaryDirectory
            : "-XX:ErrorFile=" + temporaryDirectory + "/hs_err_pid%p.log");
    properties.add("-Ddd.trace.agent.port=" + server.getAddress().getPort());
    properties.add("-Ddd.env=" + ENV);
    properties.add("-Ddd.version=" + VERSION);
    properties.add("-Ddd.profiling.enabled=true");
    properties.add("-Ddd.profiling.start-delay=" + PROFILING_START_DELAY_SECONDS);
    properties.add("-Ddd.profiling.upload.period=" + PROFILING_RECORDING_UPLOAD_PERIOD_SECONDS);
    properties.add("-Ddd.profiling.url=http://localhost:" + PortUtils.randomOpenPort() + "/");
    properties.add("-Ddd.profiling.ddprof.enabled=" + isDdprofSafe);
    properties.add("-Ddd.profiling.ddprof.alloc.enabled=" + isDdprofSafe);
    properties.add("-Ddatadog.slf4j.simpleLogger.defaultLogLevel=info");
    properties.add("-Dorg.slf4j.simpleLogger.defaultLogLevel=info");
    properties.add("-Ddd.site=");
    properties.add("-Djava.util.prefs.userRoot=" + preferencesDirectory);
    properties.add("-Ddd.service.name=" + SERVICE_NAME);
    properties.add("-Ddd.telemetry.heartbeat.interval=5");
    if (!isJ9()) {
      properties.add(
          "-XX:OnError="
              + temporaryDirectory
              + "/dd_crash_uploader."
              + getScriptExtension()
              + " %p");
    }

    // Disable CDS to avoid SIGSEGVs on Linux arm64.
    if (OperatingSystem.isLinux() && OperatingSystem.architecture().isArm64()) {
      properties.add("-Xshare:off");
    }
    return properties;
  }

  private void stopProcess() throws InterruptedException {
    if (testedProcess == null) {
      return;
    }

    if (testedProcess.isAlive()) {
      System.err.println("Destroying instrumented process");
      testedProcess.destroy();
      if (!testedProcess.waitFor(5, SECONDS)) {
        System.err.println("Destroying instrumented process (forced)");
        testedProcess.destroyForcibly();
        if (!testedProcess.waitFor(10, SECONDS)) {
          throw new IllegalStateException("Instrumented process failed to exit after SIGKILL");
        }
      }
    }
    System.err.println("Instrumented process exited with " + testedProcess.exitValue());
  }

  private void assertNoErrorLogs() throws IOException {
    if (logFilePath == null || !Files.exists(logFilePath)) {
      return;
    }

    List<String> errorLogs = new ArrayList<>();
    try (BufferedReader reader = Files.newBufferedReader(logFilePath)) {
      String line;
      while ((line = reader.readLine()) != null) {
        if (isErrorLog(line)) {
          errorLogs.add(line);
        }
      }
    }

    StringBuilder message = new StringBuilder("Test application log contains errors:\n");
    for (int index = 0; index < errorLogs.size(); index++) {
      message.append(index + 1).append(": ").append(errorLogs.get(index)).append('\n');
    }
    assertTrue(errorLogs.isEmpty(), message.toString());
  }

  private static boolean isErrorLog(String line) {
    if (line.contains(
        "ERROR com.datadog.profiling.controller.ProfilingSystem - Fatal exception in profiling thread, trying to continue")) {
      return false;
    }
    if (line.contains("I/O reactor terminated abnormally")) {
      return false;
    }
    if (line.contains(
        "ERROR com.datadog.profiling.controller.ProfilingSystem - Fatal exception during profiling startup")) {
      return false;
    }
    if (line.contains(
        "ERROR datadog.trace.agent.jmxfetch.JMXFetch - jmx collector exited with result: 0")) {
      return false;
    }
    return line.contains("ERROR")
        || line.contains("ASSERTION FAILED")
        || line.contains("Failed to handle exception in instrumentation");
  }

  protected static String readResource(Class<?> resourceClass, String name) {
    try (BufferedReader reader =
        new BufferedReader(new InputStreamReader(resourceClass.getResourceAsStream(name), UTF_8))) {
      return reader.lines().collect(joining("\n"));
    } catch (Throwable e) {
      throw new IllegalStateException("Failed to read resource: " + name, e);
    }
  }

  private void throwIfTraceDecodingFailed() throws Exception {
    if (traceDecodingFailure != null) {
      rethrow(traceDecodingFailure);
    }
  }

  private static Path requiredDirectory(String propertyName) {
    Path path = requiredPath(propertyName);
    assertTrue(Files.isDirectory(path), propertyName + " is not a directory: " + path);
    return path;
  }

  private static Path requiredFile(String propertyName) {
    Path path = requiredPath(propertyName);
    assertTrue(Files.isRegularFile(path), propertyName + " is not a file: " + path);
    return path;
  }

  private static Path requiredPath(String propertyName) {
    String value = System.getProperty(propertyName);
    assertTrue(
        value != null && !value.isEmpty(),
        "Expected system property " + propertyName + ". Smoke tests must run from Gradle.");
    return Paths.get(value);
  }

  private static String getScriptExtension() {
    return OperatingSystem.isWindows() ? "bat" : "sh";
  }

  private static Throwable runCleanup(Throwable currentFailure, CleanupAction action) {
    try {
      action.run();
      return currentFailure;
    } catch (Throwable failure) {
      return addFailure(currentFailure, failure);
    }
  }

  private static Throwable addFailure(Throwable currentFailure, Throwable nextFailure) {
    if (currentFailure == null) {
      return nextFailure;
    }
    if (currentFailure != nextFailure) {
      currentFailure.addSuppressed(nextFailure);
    }
    return currentFailure;
  }

  private static void rethrow(Throwable failure) throws Exception {
    if (failure instanceof Error) {
      throw (Error) failure;
    }
    if (failure instanceof Exception) {
      throw (Exception) failure;
    }
    throw new IllegalStateException(failure);
  }

  @FunctionalInterface
  private interface CleanupAction {
    void run() throws Exception;
  }
}
