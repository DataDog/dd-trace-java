package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.squareup.moshi.Moshi;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
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
import org.junit.jupiter.api.BeforeEach;

abstract class AbstractCrashtrackingSmokeTest {
  static final OutputThreads OUTPUT = new OutputThreads();
  static final Path LOG_FILE_DIR =
      Paths.get(System.getProperty("datadog.smoketest.builddir"), "reports");

  MockWebServer tracingServer;
  final BlockingQueue<CrashTelemetryData> crashEvents = new LinkedBlockingQueue<>();
  final Moshi moshi = new Moshi.Builder().build();
  Path tempDir;

  @BeforeEach
  void setUpTracingServer() throws Exception {
    tempDir = Files.createTempDirectory("dd-smoketest-");
    crashEvents.clear();
    tracingServer = new MockWebServer();
    tracingServer.setDispatcher(
        new Dispatcher() {
          @Override
          public MockResponse dispatch(RecordedRequest request) {
            String data = request.getBody().readString(StandardCharsets.UTF_8);
            System.out.println("URL ====== " + request.getPath());
            if ("/telemetry/proxy/api/v2/apmtelemetry".equals(request.getPath())) {
              try {
                MinimalTelemetryData minimal =
                    moshi.adapter(MinimalTelemetryData.class).fromJson(data);
                if ("logs".equals(minimal.request_type)) {
                  crashEvents.add(moshi.adapter(CrashTelemetryData.class).fromJson(data));
                }
              } catch (IOException e) {
                System.out.println("Unable to parse: " + e);
              }
            }
            System.out.println(data);
            return new MockResponse().setResponseCode(200);
          }
        });
    OUTPUT.clearMessages();
  }

  @AfterEach
  void tearDownTracingServer() throws Exception {
    tracingServer.shutdown();
    try (Stream<Path> files = Files.walk(tempDir)) {
      files.sorted(Comparator.reverseOrder()).map(Path::toFile).forEach(File::delete);
    }
    Files.deleteIfExists(tempDir);
  }

  @AfterAll
  static void shutdownOutputThreads() {
    OUTPUT.close();
  }

  protected long crashDataTimeoutMs() {
    return 10 * 1000;
  }

  protected String assertCrashPing() throws InterruptedException, IOException {
    CrashTelemetryData crashData = crashEvents.poll(crashDataTimeoutMs(), TimeUnit.MILLISECONDS);
    assertNotNull(crashData, "Crash ping not sent");
    assertTrue(crashData.payload.get(0).tags.contains("is_crash_ping:true"), "Not a crash ping");
    final Object uuid =
        moshi.adapter(Map.class).fromJson(crashData.payload.get(0).message).get("crash_uuid");
    assertNotNull(uuid, "crash uuid not found");
    return uuid.toString();
  }

  protected CrashTelemetryData assertCrashData(String uuid)
      throws InterruptedException, IOException {
    CrashTelemetryData crashData = crashEvents.poll(crashDataTimeoutMs(), TimeUnit.MILLISECONDS);
    assertNotNull(crashData, "Crash data not uploaded");
    assertTrue(
        crashData.payload.get(0).tags.contains("severity:crash"), "Expected severity:crash tag");
    final Object receivedUuid =
        moshi.adapter(Map.class).fromJson(crashData.payload.get(0).message).get("uuid");
    assertEquals(uuid, receivedUuid, "crash uuid should match the one sent with the ping");
    return crashData;
  }

  static String javaPath() {
    String sep = FileSystems.getDefault().getSeparator();
    return System.getProperty("java.home") + sep + "bin" + sep + "java";
  }

  static String appShadowJar() {
    return System.getProperty("datadog.smoketest.app.shadowJar.path");
  }

  static String agentShadowJar() {
    return System.getProperty("datadog.smoketest.agent.shadowJar.path");
  }
}
