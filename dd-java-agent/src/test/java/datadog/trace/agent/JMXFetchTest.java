package datadog.trace.agent;

import static datadog.trace.agent.test.IntegrationTestUtils.runOnSeparateJvm;
import static datadog.trace.agent.test.IntegrationTestUtils.startOnSeparateJvm;
import static java.util.Arrays.asList;
import static java.util.Collections.emptyList;
import static java.util.Collections.emptyMap;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import jvmbootstraptest.AgentLoadedChecker;
import jvmbootstraptest.JmxCollectorStateChecker;
import jvmbootstraptest.JmxStartedChecker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.tabletest.junit.TableTest;

@Timeout(30)
class JMXFetchTest {

  private static final String METRIC_CONFIG_PREFIX =
      "jmxfetch/datadog/trace/agent/jmxfetch/metricconfigs/";
  private static final Pattern READING_METRIC_CONFIG =
      Pattern.compile("Reading metric config resource (.*)");

  private DatagramSocket jmxStatsSocket;

  @TempDir private Path tempDir;

  @BeforeEach
  void setup() throws IOException {
    jmxStatsSocket = new DatagramSocket(0);
    jmxStatsSocket.setSoTimeout(30 * 1000);
  }

  @AfterEach
  void cleanup() {
    jmxStatsSocket.close();
  }

  @Test
  void testJmxFetch() throws Exception {
    // when
    // verify that JMX starts and reports metrics through the given socket.
    Process process =
        startOnSeparateJvm(
            JmxStartedChecker.class.getName(),
            asList(
                "-Ddd.jmxfetch.enabled=true",
                "-Ddd.jmxfetch.start-delay=0",
                "-Ddd.jmxfetch.statsd.port=" + jmxStatsSocket.getLocalPort(),
                "-Ddd.writer.type=DDAgentWriter"),
            singletonList("30000"),
            emptyMap(),
            System.getProperty("java.class.path"));
    try {
      List<String> lines = readLines(process.getInputStream());

      // then
      assertFalse(lines.isEmpty());
      assertEquals("READY", lines.get(lines.size() - 1));

      // when
      byte[] buf = new byte[1500];
      DatagramPacket packet = new DatagramPacket(buf, buf.length);
      jmxStatsSocket.receive(packet);
      String received = new String(packet.getData(), 0, packet.getLength());

      // then
      assertTrue(received.contains("service:" + JmxStartedChecker.class.getName()));
    } finally {
      // cleanup
      process.destroy();
    }
  }

  @Test
  void agentLoadsWhenJmxFetchIsMisconfigured() throws Exception {
    // when
    // verify the agent starts up correctly with a bogus address.
    int returnCode =
        runOnSeparateJvm(
            AgentLoadedChecker.class.getName(),
            asList(
                "-Ddd.jmxfetch.enabled=true",
                "-Ddd.jmxfetch.start-delay=0",
                "-Ddd.jmxfetch.statsd.host=example.local",
                "-Ddd.writer.type=DDAgentWriter"),
            emptyList(),
            emptyMap(),
            true);

    // then
    assertEquals(0, returnCode);
  }

  @TableTest({
    "scenario               | names           | enable | expectedConfigs",
    "no integration         | []              | true   | {}             ",
    "tomcat disabled        | [tomcat]        | false  | {}             ",
    "tomcat enabled         | [tomcat]        | true   | {tomcat}       ",
    "kafka enabled          | [kafka]         | true   | {kafka}        ",
    "tomcat, kafka enabled  | [tomcat, kafka] | true   | {tomcat, kafka}",
    "tomcat, kafka disabled | [tomcat, kafka] | false  | {}             ",
    "invalid enabled        | [invalid]       | true   | {}             "
  })
  void testJmxFetchConfig(List<String> names, boolean enable, Set<String> expectedConfigs)
      throws Exception {
    // setup
    List<String> jvmArgs =
        new ArrayList<>(
            asList(
                "-Ddd.jmxfetch.enabled=true",
                "-Ddd.jmxfetch.start-delay=0",
                "-Ddd.jmxfetch.statsd.port=" + jmxStatsSocket.getLocalPort(),
                "-Ddd.trace.debug=true",
                "-Ddd.writer.type=DDAgentWriter"));
    for (String name : names) {
      jvmArgs.add("-Ddd.jmxfetch." + name + ".enabled=" + enable);
    }
    ByteArrayOutputStream testOutput = new ByteArrayOutputStream();

    // when
    int returnCode =
        runOnSeparateJvm(
            JmxStartedChecker.class.getName(),
            jvmArgs,
            emptyList(),
            emptyMap(),
            new PrintStream(testOutput));

    Set<String> actualConfig = new HashSet<>();
    for (String line : readLines(new ByteArrayInputStream(testOutput.toByteArray()))) {
      System.out.println(line);
      Matcher match = READING_METRIC_CONFIG.matcher(line);
      if (match.find()) {
        actualConfig.add(match.group(1));
      }
    }

    // then
    assertEquals(0, returnCode);
    Set<String> expectedConfig = new HashSet<>();
    for (String config : expectedConfigs) {
      expectedConfig.add(METRIC_CONFIG_PREFIX + config + ".yaml");
    }
    assertEquals(expectedConfig, actualConfig);
  }

  @TableTest({
    "scenario                     | otlp  | tomcat | websphere | userConfig | expectedStarted | expectedDefaultConfigs                ",
    "otlp disabled                | false | false  | false     | false      | true            | [jmxfetch-config.yaml]                ",
    "otlp enabled, no integration | true  | false  | false     | false      | false           | []                                    ",
    "otlp enabled, tomcat enabled | true  | true   | false     | false      | true            | [jmxfetch-config-no-jvm-defaults.yaml]",
    "otlp enabled, websphere only | true  | false  | true      | false      | true            | [jmxfetch-websphere-config.yaml]      ",
    "otlp enabled, user config    | true  | false  | false     | true       | true            | []                                    "
  })
  void jmxCollectorStartsOnlyWhenItHasWork(
      boolean otlp,
      boolean tomcat,
      boolean websphere,
      boolean userConfig,
      boolean expectedStarted,
      List<String> expectedDefaultConfigs)
      throws Exception {
    // setup
    List<String> jvmArgs =
        new ArrayList<>(
            asList(
                "-Ddd.jmxfetch.enabled=true",
                "-Ddd.jmxfetch.start-delay=0",
                "-Ddd.jmxfetch.statsd.port=" + jmxStatsSocket.getLocalPort(),
                "-Ddd.trace.debug=true",
                "-Ddd.writer.type=DDAgentWriter"));
    if (otlp) {
      jvmArgs.add("-Ddd.metrics.otel.enabled=true");
      jvmArgs.add("-Ddd.metrics.otel.exporter=otlp");
    }
    if (tomcat) {
      jvmArgs.add("-Ddd.jmxfetch.tomcat.enabled=true");
    }
    if (websphere) {
      jvmArgs.add("-Ddd.jmxfetch.websphere.enabled=true");
    }
    if (userConfig) {
      Files.write(
          tempDir.resolve("user.yaml"),
          asList(
              "init_config:",
              "  is_jmx: true",
              "instances:",
              "  - jvm_direct: true",
              "    conf:",
              "      - include:",
              "          domain: java.lang",
              "          type: Memory"));
      jvmArgs.add("-Ddd.jmxfetch.config.dir=" + tempDir);
      jvmArgs.add("-Ddd.jmxfetch.config=user.yaml");
    }
    ByteArrayOutputStream testOutput = new ByteArrayOutputStream();

    // when
    int returnCode =
        runOnSeparateJvm(
            JmxCollectorStateChecker.class.getName(),
            jvmArgs,
            emptyList(),
            emptyMap(),
            new PrintStream(testOutput));

    // then
    assertEquals(0, returnCode);
    List<String> lines = readLines(new ByteArrayInputStream(testOutput.toByteArray()));
    String expectedLine = "JMX_COLLECTOR_STARTED=" + expectedStarted;
    assertTrue(
        lines.stream().anyMatch(line -> line.endsWith(expectedLine)),
        "Expected '" + expectedLine + "' in output: " + lines);
    // the collector must be skipped on purpose, not because JMXFetch failed to start
    assertEquals(
        !expectedStarted,
        lines.stream().anyMatch(line -> line.contains("JMXFetch collector not started")),
        "Unexpected skip log state in output: " + lines);
    // logged synchronously by JMXFetch.run() in premain, so it is in the output when the JVM exits
    String expectedConfigsLine = "JMXFetch default instance configs: " + expectedDefaultConfigs;
    assertTrue(
        lines.stream().anyMatch(line -> line.endsWith(expectedConfigsLine)),
        "Expected '" + expectedConfigsLine + "' in output: " + lines);
  }

  private static List<String> readLines(InputStream input) throws IOException {
    List<String> lines = new ArrayList<>();
    try (BufferedReader reader = new BufferedReader(new InputStreamReader(input))) {
      String line;
      while ((line = reader.readLine()) != null) {
        lines.add(line);
      }
    }
    return lines;
  }
}
