package datadog.smoketest;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.Moshi;
import datadog.trace.api.Platform;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
  private static Path LOG_FILE_DIR;
  private MockWebServer tracingServer;
  private BlockingQueue<CrashTelemetryData> crashEvents = new LinkedBlockingQueue<>();

  @BeforeAll
  static void setupAll() {
    // Only Hotspot based implementation are supported
    assumeFalse(Platform.isJ9());

    LOG_FILE_DIR = Paths.get(System.getProperty("datadog.smoketest.builddir"), "reports");
  }

  private Path tempDir;
  private static OutputThreads outputThreads = new OutputThreads();

  @BeforeEach
  void setup() throws Exception {
    tempDir = Files.createTempDirectory("dd-smoketest-");

    crashEvents.clear();

    Moshi moshi = new Moshi.Builder().build();
    tracingServer = new MockWebServer();
    tracingServer.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(final RecordedRequest request) throws InterruptedException {
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
            System.out.println("URL ====== " + request.getPath());
            System.out.println(data);

            return new MockResponse().setResponseCode(200);
          }
        });
    //    tracingServer.start(8126);
    synchronized (outputThreads.testLogMessages) {
      outputThreads.testLogMessages.clear();
    }
  }

  @AfterEach
  void teardown() throws Exception {
    tracingServer.shutdown();

    try (Stream<Path> fileStream = Files.walk(tempDir)) {
      fileStream.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
    Files.deleteIfExists(tempDir);
  }

  @AfterAll
  static void shutdown() {
    outputThreads.close();
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
    return Platform.isWindows() ? "bat" : "sh";
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
                appShadowJar(),
                script.toString()));
    pb.environment().put("DD_TRACE_AGENT_PORT", String.valueOf(tracingServer.getPort()));

    Process p = pb.start();
    outputThreads.captureOutput(
        p, LOG_FILE_DIR.resolve("testProcess.testCrashTracking.log").toFile());

    assertNotEquals(0, p.waitFor(), "Application should have crashed");
    assertCrashData();
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
                appShadowJar(),
                script.toString()));
    pb.environment().put("DD_TRACE_AGENT_PORT", String.valueOf(tracingServer.getPort()));

    Process p = pb.start();
    outputThreads.captureOutput(
        p, LOG_FILE_DIR.resolve("testProcess.testCrashTrackingLegacy.log").toFile());

    assertNotEquals(0, p.waitFor(), "Application should have crashed");

    assertNotEquals(0, p.waitFor(), "Application should have crashed");
    assertCrashData();
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

    ProcessBuilder pb =
        new ProcessBuilder(
            Arrays.asList(
                javaPath(),
                "-javaagent:" + agentShadowJar(),
                "-XX:OnOutOfMemoryError=" + onErrorValue,
                "-XX:ErrorFile=" + errorFile,
                "-XX:+CrashOnOutOfMemoryError", // Use OOME to trigger crash
                "-Ddd.dogstatsd.start-delay=0", // Minimize the delay to initialize JMX and create
                // the scripts
                "-Ddd.trace.enabled=false",
                "-jar",
                appShadowJar(),
                script.toString()));

    Process p = pb.start();
    outputThreads.captureOutput(
        p, LOG_FILE_DIR.resolve("testProcess.testOomeTracking.log").toFile());
    pb.environment().put("DD_DOGSTATSD_PORT", String.valueOf(tracingServer.getPort()));

    assertNotEquals(0, p.waitFor(), "Application should have crashed");

    assertOutputContains("com.datadog.crashtracking.OOMENotifier - OOME event sent");
    assertOutputContains("OOME Event generated successfully");
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
                "-XX:OnOutOfMemoryError=" + onOomeValue,
                "-XX:OnError=" + onErrorValue,
                "-XX:ErrorFile=" + errorFile,
                "-XX:+CrashOnOutOfMemoryError", // Use OOME to trigger crash
                "-Ddd.dogstatsd.start-delay=0", // Minimize the delay to initialize JMX and create
                // the scripts
                "-Ddd.trace.enabled=false",
                "-jar",
                appShadowJar(),
                oomeScript.toString()));
    pb.environment().put("DD_TRACE_AGENT_PORT", String.valueOf(tracingServer.getPort()));
    pb.environment().put("DD_DOGSTATSD_PORT", String.valueOf(tracingServer.getPort()));

    Process p = pb.start();
    outputThreads.captureOutput(
        p, LOG_FILE_DIR.resolve("testProcess.testCombineTracking.log").toFile());

    assertNotEquals(0, p.waitFor(), "Application should have crashed");

    // Crash uploader did get triggered
    assertCrashData();

    // OOME notifier did get triggered
    assertOutputContains("com.datadog.crashtracking.OOMENotifier - OOME event sent");
    assertOutputContains("OOME Event generated successfully");
  }

  private void assertOutputContains(String s) {
    try {
      outputThreads.processTestLogLines((line) -> line.contains(s));
    } catch (TimeoutException e) {
      // FIXME JUNit fail() is more correct but doesn't work. SEE:
      // https://github.com/gradle/gradle/issues/27871
      // fixed in Gradle version 8.7
      // fail("String: '" + s + "' not found in output");
      throw new RuntimeException("String: '" + s + "' not found in output");
    }
  }

  private void assertCrashData() throws InterruptedException {
    CrashTelemetryData crashData = crashEvents.poll(10, TimeUnit.SECONDS);
    assertNotNull(crashData, "Crash data not uploaded");
    assertThat(crashData.payload.get(0).message, containsString("OutOfMemory"));
    assertThat(crashData.payload.get(0).tags, containsString("severity:crash"));
  }
}
