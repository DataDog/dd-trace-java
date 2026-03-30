package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import datadog.environment.JavaVirtualMachine;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

public class OpenJ9CrashtrackingSmokeTest extends AbstractCrashtrackingSmokeTest {
  @BeforeAll
  static void setupAll() {
    assumeTrue(JavaVirtualMachine.isJ9(), "OpenJ9 launcher required");
  }

  @Override
  protected long crashDataTimeoutMs() {
    return 30_000;
  }

  /**
   * Verifies end-to-end OpenJ9 crash tracking:
   *
   * <ol>
   *   <li>The agent detects the {@code -Xdump:tool} argument and writes the crash-uploader script
   *       to the path specified in {@code exec=}.
   *   <li>The application crashes itself via {@code sun.misc.Unsafe.putAddress(0L, 0L)}, which
   *       triggers the dump tool on the {@code gpf} event.
   *   <li>The crash-uploader uploads the javacore file and the telemetry (ping + data) arrives.
   * </ol>
   */
  @Test
  void testCrashTracking() throws Exception {
    String script = tempDir.resolve("dd_crash_uploader.sh").toString();
    String javacorePattern = tempDir.resolve("javacore.%Y%m%d.%H%M%S.%pid.%seq.txt").toString();

    List<String> jvmArgs = new ArrayList<>();
    jvmArgs.add(javaPath());
    jvmArgs.add("-javaagent:" + agentShadowJar());
    jvmArgs.add("-Xdump:tool:events=gpf+abort,exec=" + script + " %pid");
    jvmArgs.add("-Xdump:java:file=" + javacorePattern);
    jvmArgs.add("-Xdump:system:none");
    jvmArgs.add("-Xdump:snap:none");
    jvmArgs.add("-Ddd.dogstatsd.start-delay=0");
    jvmArgs.add("-Ddd.trace.enabled=false");
    jvmArgs.add("-Ddd.test.crash_script=" + script);
    jvmArgs.add("-cp");
    jvmArgs.add(appShadowJar());
    jvmArgs.add("datadog.smoketest.crashtracking.OpenJ9CrashtrackingTestApplication");

    ProcessBuilder pb = new ProcessBuilder(jvmArgs);
    pb.directory(tempDir.toFile());
    pb.environment().put("DD_TRACE_AGENT_PORT", String.valueOf(tracingServer.getPort()));

    Process p = pb.start();
    OUTPUT.captureOutput(p, LOG_FILE_DIR.resolve("testProcess.openj9CrashTracking.log").toFile());

    // OpenJ9 runs the dump tool synchronously on crash, so the upload completes before JVM exits
    assertTrue(p.waitFor(60, TimeUnit.SECONDS), "JVM did not exit within 60s after crash");
    assertTrue(p.exitValue() != 0, "JVM should have crashed (non-zero exit code)");

    CrashTelemetryData crashData = assertCrashData(assertCrashPing());
    assertAdditionalData(crashData);
  }

  @SuppressWarnings("unchecked")
  private void assertAdditionalData(CrashTelemetryData crashData) throws IOException {
    Map<Object, Object> crashDataMap =
        moshi.adapter(Map.class).fromJson(crashData.payload.get(0).message);
    Map<Object, Object> error = (Map<Object, Object>) crashDataMap.get("error");
    assertEquals("SIGSEGV", error.get("kind"));

    Map<Object, Object> stack = (Map<Object, Object>) error.get("stack");
    List<Object> frames = (List<Object>) stack.get("frames");
    assertTrue(
        frames.stream()
            .filter(Map.class::isInstance)
            .map(Map.class::cast)
            .map(frame -> frame.get("function"))
            .anyMatch(
                "datadog/smoketest/crashtracking/OpenJ9CrashtrackingTestApplication.main"::equals),
        "Expected application main frame");
  }
}
