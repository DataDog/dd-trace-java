package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.datadoghq.profiler.Platform;
import datadog.environment.JavaVirtualMachine;
import datadog.environment.OperatingSystem;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/*
 * NOTE: The current implementation of crash tracking doesn't work with ancient version of bash
 * that ships with OS X by default.
 */
public class CrashtrackingSmokeTest extends AbstractCrashtrackingSmokeTest {
  private TestUDPServer udpServer;

  @BeforeAll
  static void setupAll() {
    // Only Hotspot based implementation are supported
    assumeFalse(JavaVirtualMachine.isJ9());
  }

  @BeforeEach
  void setUpUdpServer() throws Exception {
    udpServer = new TestUDPServer();
    udpServer.start();
  }

  @AfterEach
  void tearDownUdpServer() throws Exception {
    udpServer.close();
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
    String script = tempDir.resolve("dd_crash_uploader." + getExtension()).toString();
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
    String script = tempDir.resolve("dd_crash_uploader." + getExtension()).toString();
    String errorFile = tempDir.resolve("hs_err.log").toString();

    ProcessBuilder pb =
        new ProcessBuilder(
            Arrays.asList(
                javaPath(),
                "-javaagent:" + agentShadowJar(),
                "-Xmx96m",
                "-Xms96m",
                "-XX:OnError=" + script,
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
    String script = tempDir.resolve("dd_oome_notifier." + getExtension()).toString();
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
    String errorScript = tempDir.resolve("dd_crash_uploader." + getExtension()).toString();
    String oomeScript = tempDir.resolve("dd_oome_notifier." + getExtension()).toString();
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

  /**
   * Verifies that the OOME notifier script correctly unsets inherited JVM environment variables.
   * Without the fix, the child JVM spawned by the script would inherit JDK_JAVA_OPTIONS containing
   * JMX port-binding flags, causing a BindException and losing the OOME event.
   *
   * @see <a href="https://github.com/DataDog/dd-trace-java/issues/10766">#10766</a>
   */
  @Test
  void testOomeTrackingWithInheritedEnvVars() throws Exception {
    int jmxPort = findFreePort();

    String script = tempDir.resolve("dd_oome_notifier." + getExtension()).toString();
    String onErrorValue = script + " %p";
    String errorFile = tempDir.resolve("hs_err_pid%p.log").toString();

    String onOOMEArg =
        !Platform.isLinux()
            ? "-XX:OnOutOfMemoryError=" + onErrorValue
            : "-Ddd.crashtracking.debug.autoconfig.enable=true";

    List<String> processArgs = new ArrayList<>();
    processArgs.add(javaPath());
    processArgs.add("-javaagent:" + agentShadowJar());
    processArgs.add("-Xmx96m");
    processArgs.add("-Xms96m");
    if (!onOOMEArg.isEmpty()) {
      processArgs.add(onOOMEArg);
    }
    processArgs.add("-XX:ErrorFile=" + errorFile);
    processArgs.add("-XX:+CrashOnOutOfMemoryError");
    processArgs.add("-Ddd.dogstatsd.start-delay=0");
    processArgs.add("-Ddd.trace.enabled=false");
    processArgs.add("-jar");
    processArgs.add(appShadowJar());

    ProcessBuilder pb = new ProcessBuilder(processArgs);
    pb.environment().put("DD_DOGSTATSD_PORT", String.valueOf(udpServer.getPort()));
    // Simulate admission controller injecting JMX flags via JDK_JAVA_OPTIONS
    pb.environment()
        .put(
            "JDK_JAVA_OPTIONS",
            "-Dcom.sun.management.jmxremote"
                + " -Dcom.sun.management.jmxremote.port="
                + jmxPort
                + " -Dcom.sun.management.jmxremote.rmi.port="
                + jmxPort
                + " -Dcom.sun.management.jmxremote.authenticate=false"
                + " -Dcom.sun.management.jmxremote.ssl=false");

    System.out.println("==> Process args: " + pb.command());
    System.out.println("==> JMX port: " + jmxPort);

    Process p = pb.start();
    OUTPUT.captureOutput(
        p, LOG_FILE_DIR.resolve("testProcess.testOomeTrackingWithInheritedEnvVars.log").toFile());

    assertExpectedCrash(p);
    assertOOMEvent();
  }

  /**
   * Verifies that the crash uploader script correctly unsets inherited JVM environment variables.
   * Without the fix, the child JVM spawned by the script would inherit JDK_JAVA_OPTIONS containing
   * JMX port-binding flags, causing a BindException and losing the crash data.
   *
   * @see <a href="https://github.com/DataDog/dd-trace-java/issues/10766">#10766</a>
   */
  @Test
  void testCrashTrackingWithInheritedEnvVars() throws Exception {
    int jmxPort = findFreePort();

    String script = tempDir.resolve("dd_crash_uploader." + getExtension()).toString();
    String onErrorValue = script + " %p";
    String errorFile = tempDir.resolve("hs_err.log").toString();

    String onErrorArg =
        !Platform.isLinux()
            ? "-XX:OnError=" + onErrorValue
            : "-Ddd.crashtracking.debug.autoconfig.enable=true";

    List<String> processArgs = new ArrayList<>();
    processArgs.add(javaPath());
    processArgs.add("-javaagent:" + agentShadowJar());
    processArgs.add("-Xmx96m");
    processArgs.add("-Xms96m");
    if (!onErrorArg.isEmpty()) {
      processArgs.add(onErrorArg);
    }
    processArgs.add("-XX:ErrorFile=" + errorFile);
    processArgs.add("-XX:+CrashOnOutOfMemoryError");
    processArgs.add("-Ddd.dogstatsd.start-delay=0");
    processArgs.add("-Ddd.trace.enabled=false");
    processArgs.add("-jar");
    processArgs.add(appShadowJar());

    ProcessBuilder pb = new ProcessBuilder(processArgs);
    pb.environment().put("DD_TRACE_AGENT_PORT", String.valueOf(tracingServer.getPort()));
    // Simulate admission controller injecting JMX flags via JDK_JAVA_OPTIONS
    pb.environment()
        .put(
            "JDK_JAVA_OPTIONS",
            "-Dcom.sun.management.jmxremote"
                + " -Dcom.sun.management.jmxremote.port="
                + jmxPort
                + " -Dcom.sun.management.jmxremote.rmi.port="
                + jmxPort
                + " -Dcom.sun.management.jmxremote.authenticate=false"
                + " -Dcom.sun.management.jmxremote.ssl=false");

    System.out.println("==> Process args: " + pb.command());
    System.out.println("==> JMX port: " + jmxPort);

    Process p = pb.start();
    OUTPUT.captureOutput(
        p, LOG_FILE_DIR.resolve("testProcess.testCrashTrackingWithInheritedEnvVars.log").toFile());

    assertExpectedCrash(p);
    assertCrashData(assertCrashPing());
  }

  private static int findFreePort() throws IOException {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static void assertExpectedCrash(Process p) throws InterruptedException {
    // exit code -1 means the test application exited prematurely
    // exit code > 0 means the test application crashed, as expected
    assertTrue(p.waitFor() > 0, "Application should have crashed");
  }

  @Override
  protected CrashTelemetryData assertCrashData(String uuid)
      throws InterruptedException, IOException {
    CrashTelemetryData crashData = super.assertCrashData(uuid);
    assertTrue(crashData.payload.get(0).message.contains("Java heap space"));
    return crashData;
  }

  private void assertOOMEvent() throws InterruptedException {
    String event;
    do {
      event = udpServer.getMessages().poll(crashDataTimeoutMs(), TimeUnit.MILLISECONDS);
    } while (event != null && !event.startsWith("_e"));

    assertNotNull(event, "OOM Event not received");

    assertTrue(event.contains(":OutOfMemoryError"));
    assertTrue(event.contains("t:error"));
    assertTrue(event.contains("s:java"));
  }
}
