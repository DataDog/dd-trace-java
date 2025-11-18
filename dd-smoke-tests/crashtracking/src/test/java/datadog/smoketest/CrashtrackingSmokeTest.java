package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.datadoghq.profiler.Platform;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/*
 * NOTE: The current implementation of crash tracking doesn't work with ancient version of bash
 * that ships with OS X by default.
 */
public class CrashtrackingSmokeTest {
  private static final long DATA_TIMEOUT_MS = 10 * 1000;
  private static final OutputThreads OUTPUT = new OutputThreads();
  private static final Path LOG_FILE_DIR =
      Paths.get(System.getProperty("datadog.smoketest.builddir"), "reports");

  private MockWebServer tracingServer;
  private TestUDPServer udpServer;
  private final BlockingQueue<CrashTelemetryData> crashEvents = new LinkedBlockingQueue<>();
  private final Moshi moshi = new Moshi.Builder().build();

  @BeforeAll
  static void setupAll() {
    // Only Hotspot based implementation are supported
    assumeFalse(JavaVirtualMachine.isJ9());
  }

  private Path tempDir;

  @BeforeEach
  void setup() throws Exception {
    tempDir = Files.createTempDirectory("dd-smoketest-");

    crashEvents.clear();

    tracingServer = new MockWebServer();
    tracingServer.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(final RecordedRequest request) {
            System.out.println("URL ====== " + request.getPath());

            String data = request.getBody().readString(StandardCharsets.UTF_8);

            if ("/telemetry/proxy/api/v2/apmtelemetry".equals(request.getPath())) {
              try {
                JsonAdapter<MinimalTelemetryData> adapter =
                    moshi.adapter(MinimalTelemetryData.class);
                MinimalTelemetryData minimal = adapter.fromJson(data);
                if ("logs".equals(minimal.request_type)) {
                  JsonAdapter<CrashTelemetryData> crashAdapter =
                      moshi.adapter(CrashTelemetryData.class);
                  crashEvents.add(crashAdapter.fromJson(data));
                }
              } catch (IOException e) {
                System.out.println("Unable to parse " + e);
              }
            }

            System.out.println(data);

            return new MockResponse().setResponseCode(200);
          }
        });

    udpServer = new TestUDPServer();
    udpServer.start();

    OUTPUT.clearMessages();
  }

  @AfterEach
  void teardown() throws Exception {
    tracingServer.shutdown();
    udpServer.close();

    try (Stream<Path> fileStream = Files.walk(tempDir)) {
      fileStream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
    Files.deleteIfExists(tempDir);
  }

  @AfterAll
  static void shutdown() {
    OUTPUT.close();
  }

  private static String javaPath() {
    final String separator = FileSystems.getDefault().getSeparator();
    return System.getProperty("java.home") + separator + "bin" + separator + "java";
  }

  private static String appShadowJar() {
    return System.getProperty("datadog.smoketest.app.shadowJar.path");
  }

  private static String agentShadowJar() {
    return System.getProperty("datadog.smoketest.agent.shadowJar.path");
  }

  private static String getExtension() {
    return OperatingSystem.isWindows() ? "bat" : "sh";
  }

  @Test
  void testAutoInjection() throws Exception {
    assumeTrue(OperatingSystem.isLinux()); // we support only linux ATM

    ProcessBuilder pb =
        new ProcessBuilder(
            Arrays.asList(
                javaPath(),
                "-javaagent:" + agentShadowJar(),
                "-Xmx96m",
                "-Xms96m",
                "-XX:+CrashOnOutOfMemoryError", // Use OOME to trigger crash
                "-Ddd.dogstatsd.start-delay=0", // Minimize the delay to initialize JMX and create
                // no need to specify the scripts
                "-Ddd.trace.enabled=false",
                "-jar",
                appShadowJar()));

    pb.environment().put("DD_TRACE_AGENT_PORT", String.valueOf(tracingServer.getPort()));

    Process p = pb.start();
    OUTPUT.captureOutput(
        p, LOG_FILE_DIR.resolve("testProcess.testCrashTrackingInjected.log").toFile());

    assertExpectedCrash(p);
  }

  /*
   * NOTE: The current implementation of crash tracking doesn't work with ancient version of bash
   * that ships with OS X by default.
   */
  @Test
  void testCrashTracking() throws Exception {
    Path script = tempDir.resolve("dd_crash_uploader." + getExtension());
    String onErrorValue = script + " %p";
    String errorFile = tempDir.resolve("hs_err.log").toString();

    String onErrorArg =
        !Platform.isLinux()
            ? "-XX:OnError=" + onErrorValue
            : "-Ddd.crashtracking.debug.autoconfig.enable=true"; // on Linux we can automatically
    // inject the arg
    List<String> processArgs = new ArrayList<>();
    processArgs.add(javaPath());
    processArgs.add("-javaagent:" + agentShadowJar());
    processArgs.add("-Xmx96m");
    processArgs.add("-Xms96m");
    if (!onErrorArg.isEmpty()) {
      processArgs.add(onErrorArg);
    }
    processArgs.add("-XX:ErrorFile=" + errorFile);
    processArgs.add("-XX:+CrashOnOutOfMemoryError"); // Use OOME to trigger crash
    processArgs.add(
        "-Ddd.dogstatsd.start-delay=0"); // Minimize the delay to initialize JMX and create the
    // scripts
    processArgs.add("-Ddd.trace.enabled=false");
    processArgs.add("-jar");
    processArgs.add(appShadowJar());
    ProcessBuilder pb = new ProcessBuilder(processArgs);
    pb.environment().put("DD_TRACE_AGENT_PORT", String.valueOf(tracingServer.getPort()));

    Process p = pb.start();
    OUTPUT.captureOutput(p, LOG_FILE_DIR.resolve("testProcess.testCrashTracking.log").toFile());

    assertExpectedCrash(p);
    assertCrashData(assertCrashPing());
  }

  /*
   * NOTE: The current implementation of crash tracking doesn't work with ancient version of bash
   * that ships with OS X by default.
   */
  @Test
  void testCrashTrackingLegacy() throws Exception {
    Path script = tempDir.resolve("dd_crash_uploader." + getExtension());
    String onErrorValue = script.toString();
    String errorFile = tempDir.resolve("hs_err.log").toString();

    ProcessBuilder pb =
        new ProcessBuilder(
            Arrays.asList(
                javaPath(),
                "-javaagent:" + agentShadowJar(),
                "-Xmx96m",
                "-Xms96m",
                "-XX:OnError=" + onErrorValue,
                "-XX:ErrorFile=" + errorFile,
                "-XX:+CrashOnOutOfMemoryError", // Use OOME to trigger crash
                "-Ddd.dogstatsd.start-delay=0", // Minimize the delay to initialize JMX and create
                // the scripts
                "-Ddd.trace.enabled=false",
                "-jar",
                appShadowJar()));
    pb.environment().put("DD_TRACE_AGENT_PORT", String.valueOf(tracingServer.getPort()));

    Process p = pb.start();
    OUTPUT.captureOutput(
        p, LOG_FILE_DIR.resolve("testProcess.testCrashTrackingLegacy.log").toFile());

    assertExpectedCrash(p);

    assertCrashData(assertCrashPing());
  }

  /*
   * NOTE: The current implementation of crash tracking doesn't work with ancient version of bash
   * that ships with OS X by default.
   */
  @Test
  void testOomeTracking() throws Exception {
    Path script = tempDir.resolve("dd_oome_notifier." + getExtension());
    String onErrorValue = script + " %p";
    String errorFile = tempDir.resolve("hs_err_pid%p.log").toString();

    String onOOMEArg =
        !Platform.isLinux()
            ? "-XX:OnOutOfMemoryError=" + onErrorValue
            : "-Ddd.crashtracking.debug.autoconfig.enable=true"; // on Linux we can automatically
    // inject the arg

    List<String> processArgs = new ArrayList<>();
    processArgs.add(javaPath());
    processArgs.add("-javaagent:" + agentShadowJar());
    processArgs.add("-Xmx96m");
    processArgs.add("-Xms96m");
    if (!onOOMEArg.isEmpty()) {
      processArgs.add(onOOMEArg);
    }
    processArgs.add("-XX:ErrorFile=" + errorFile);
    processArgs.add("-XX:+CrashOnOutOfMemoryError"); // Use OOME to trigger crash
    processArgs.add(
        "-Ddd.dogstatsd.start-delay=0"); // Minimize the delay to initialize JMX and create the
    // scripts
    processArgs.add("-Ddd.trace.enabled=false");
    processArgs.add("-jar");
    processArgs.add(appShadowJar());

    ProcessBuilder pb = new ProcessBuilder(processArgs);
    pb.environment().put("DD_DOGSTATSD_PORT", String.valueOf(udpServer.getPort()));

    System.out.println("==> Process args: " + pb.command());

    Process p = pb.start();
    OUTPUT.captureOutput(p, LOG_FILE_DIR.resolve("testProcess.testOomeTracking.log").toFile());

    assertExpectedCrash(p);
    assertOOMEvent();
  }

  @Test
  void testCombineTracking() throws Exception {
    Path errorScript = tempDir.resolve("dd_crash_uploader." + getExtension());
    Path oomeScript = tempDir.resolve("dd_oome_notifier." + getExtension());
    String onErrorValue = errorScript + " %p";
    String onOomeValue = oomeScript + " %p";
    String errorFile = tempDir.resolve("hs_err.log").toString();

    ProcessBuilder pb =
        new ProcessBuilder(
            Arrays.asList(
                javaPath(),
                "-javaagent:" + agentShadowJar(),
                "-Xmx96m",
                "-Xms96m",
                "-XX:OnOutOfMemoryError=" + onOomeValue,
                "-XX:OnError=" + onErrorValue,
                "-XX:ErrorFile=" + errorFile,
                "-XX:+CrashOnOutOfMemoryError", // Use OOME to trigger crash
                "-Ddd.dogstatsd.start-delay=0", // Minimize the delay to initialize JMX and create
                // the scripts
                "-Ddd.trace.enabled=false",
                "-jar",
                appShadowJar()));
    pb.environment().put("DD_TRACE_AGENT_PORT", String.valueOf(tracingServer.getPort()));
    pb.environment().put("DD_DOGSTATSD_PORT", String.valueOf(udpServer.getPort()));

    Process p = pb.start();
    OUTPUT.captureOutput(p, LOG_FILE_DIR.resolve("testProcess.testCombineTracking.log").toFile());

    assertExpectedCrash(p);
    assertCrashData(assertCrashPing());
    assertOOMEvent();
  }

  private static void assertExpectedCrash(Process p) throws InterruptedException {
    // exit code -1 means the test application exited prematurely
    // exit code > 0 means the test application crashed, as expected
    assertTrue(p.waitFor() > 0, "Application should have crashed");
  }

  private String assertCrashPing() throws InterruptedException, IOException {
    CrashTelemetryData crashData = crashEvents.poll(DATA_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertNotNull(crashData, "Crash ping not sent");
    assertTrue(crashData.payload.get(0).tags.contains("is_crash_ping:true"), "Not a crash ping");
    final Map<?, ?> map = moshi.adapter(Map.class).fromJson(crashData.payload.get(0).message);
    final Object uuid = map.get("crash_uuid");
    assertNotNull(uuid, "crash uuid not found");
    return uuid.toString();
  }

  private void assertCrashData(String uuid) throws InterruptedException, IOException {
    CrashTelemetryData crashData = crashEvents.poll(DATA_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    assertNotNull(crashData, "Crash data not uploaded");
    assertTrue(crashData.payload.get(0).message.contains("Process terminated by signal"));
    assertTrue(crashData.payload.get(0).tags.contains("severity:crash"));
    final Map<?, ?> map = moshi.adapter(Map.class).fromJson(crashData.payload.get(0).message);
    final Object receivedUuid = map.get("uuid");
    assertEquals(uuid, receivedUuid, "crash uuid should match the one sent with the ping");
  }

  private void assertOOMEvent() throws InterruptedException {
    String event;
    do {
      event = udpServer.getMessages().poll(DATA_TIMEOUT_MS, TimeUnit.MILLISECONDS);
    } while (event != null && !event.startsWith("_e"));

    assertNotNull(event, "OOM Event not received");

    assertTrue(event.contains(":OutOfMemoryError"));
    assertTrue(event.contains("t:error"));
    assertTrue(event.contains("s:java"));
  }
}
