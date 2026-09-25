package datadog.smoketest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.civisibility.CiVisibilitySmokeTest;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JmhSmokeTest extends CiVisibilitySmokeTest {

  private static final String TEST_SERVICE_NAME = "test-jmh-service";
  private static final int PROCESS_TIMEOUT_SECS = 120;

  private static final String PROJECT_NAME = "test-jmh-benchmark";
  private static final String BENCHMARK_CLASS = "com.example.SmokeTestBenchmark";

  private static final String JMH_CORE_JAR =
      System.getProperty("datadog.smoketest.jmh.core.jar.path");
  private static final String JMH_ANNPROC_JAR =
      System.getProperty("datadog.smoketest.jmh.annproc.jar.path");

  private static final Set<String> CI_VISIBILITY_EVENT_TYPES =
      new HashSet<>(Arrays.asList("test", "test_suite_end", "test_module_end", "test_session_end"));

  private static final List<String> BENCHMARK_DYNAMIC_TAGS =
      Arrays.asList(
          "content.metrics.['benchmark.value']",
          "content.metrics.['benchmark.error']",
          "content.metrics.['benchmark.p50']",
          "content.metrics.['benchmark.p90']",
          "content.metrics.['benchmark.p95']",
          "content.metrics.['benchmark.p99']",
          "content.metrics.['benchmark.min']",
          "content.metrics.['benchmark.max']",
          "content.metrics.['benchmark.sample_count']");

  @TempDir Path projectHome;

  static final MockBackend mockBackend = new MockBackend();

  @BeforeEach
  void resetMockBackend() {
    mockBackend.reset();
  }

  @AfterAll
  static void closeMockBackend() throws Exception {
    mockBackend.close();
  }

  @Test
  void testBenchmarkSpansAreEmitted() throws Exception {
    // only the 4 CI Visibility events are emitted.
    assertBenchmarkSpansAreEmitted(0, 4);
  }

  @Test
  void testBenchmarkSpansAreEmittedWhenForked() throws Exception {
    // when forking, the parent records one APM "command_execution" span for the forked java
    // process, hence 5 total events (4 CI Visibility + 1 process span)
    assertBenchmarkSpansAreEmitted(1, 5);
  }

  private void assertBenchmarkSpansAreEmitted(int forks, int expectedEventCount) throws Exception {
    givenBenchmarkProject();
    assertEquals(0, compileBenchmark(), "benchmark project should compile");

    Map<String, String> agentArgs = new HashMap<>();
    agentArgs.put(CiVisibilityConfig.CIVISIBILITY_BUILD_INSTRUMENTATION_ENABLED, "false");
    agentArgs.put(GeneralConfig.AGENTLESS_LOG_SUBMISSION_URL, mockBackend.getIntakeUrl());
    agentArgs.put(CiVisibilityConfig.CIVISIBILITY_CODE_COVERAGE_ENABLED, "false");

    int exitCode = runBenchmark(agentArgs, forks);
    assertEquals(0, exitCode, "JMH process should exit cleanly");

    // filter out APM spans in forked-mode
    List<Map<String, Object>> events =
        ciVisibilityEvents(mockBackend.waitForEvents(expectedEventCount));

    // validate benchmark.value is a real measure before verifyEventsAndCoverages rewrites it
    Map<String, Object> testEvent = findEvent(events, "test");
    assertNotNull(testEvent, "Expected a test span for the benchmark method");
    @SuppressWarnings("unchecked")
    Map<String, Object> metrics =
        (Map<String, Object>) ((Map<String, Object>) testEvent.get("content")).get("metrics");
    Object benchmarkValue = metrics.get("benchmark.value");
    assertNotNull(benchmarkValue, "benchmark.value should be present");
    assertTrue(((Number) benchmarkValue).doubleValue() > 0, "benchmark.value should be positive");

    // drop to reuse the same fixture between both test scenarios
    metrics.remove("benchmark.run.forks");

    verifyEventsAndCoverages(
        PROJECT_NAME,
        "jmh",
        "headless",
        events,
        mockBackend.waitForCoverages(0),
        BENCHMARK_DYNAMIC_TAGS);
  }

  private void givenBenchmarkProject() throws Exception {
    Path projectResources =
        Paths.get(getClass().getClassLoader().getResource(PROJECT_NAME).toURI());
    copyFolder(projectResources, projectHome);
    // empty .git so the tracer detects projectHome (not the build's repo) as the project root.
    Files.createDirectories(projectHome.resolve(".git"));
  }

  private int compileBenchmark() throws Exception {
    // TODO: extract to common util for JUnitConsole and JMH
    assertTrue(new File(JMH_CORE_JAR).isFile(), "JMH core jar not found: " + JMH_CORE_JAR);
    assertTrue(
        new File(JMH_ANNPROC_JAR).isFile(),
        "JMH annotation processor jar not found: " + JMH_ANNPROC_JAR);

    Path classesDir = projectHome.resolve("target/classes");
    Files.createDirectories(classesDir);

    List<String> command = new ArrayList<>();
    command.add(javacPath());
    command.addAll(Arrays.asList("-cp", JMH_CORE_JAR + File.pathSeparator + JMH_ANNPROC_JAR));
    command.addAll(
        Arrays.asList("-processorpath", JMH_ANNPROC_JAR + File.pathSeparator + JMH_CORE_JAR));
    command.addAll(Arrays.asList("-processor", "org.openjdk.jmh.generators.BenchmarkProcessor"));
    command.addAll(Arrays.asList("-d", classesDir.toString()));
    command.addAll(findJavaFiles(projectHome.resolve("src/main/java")));

    return runProcess(new ProcessBuilder(command), "javac");
  }

  private int runBenchmark(Map<String, String> additionalAgentArgs, int forks) throws Exception {
    String classpath =
        projectHome.resolve("target/classes")
            + File.pathSeparator
            + System.getProperty("java.class.path");

    List<String> command = new ArrayList<>();
    command.add(javaPath());
    command.addAll(
        buildJvmArguments(mockBackend.getIntakeUrl(), TEST_SERVICE_NAME, additionalAgentArgs));
    Collections.addAll(command, "-cp", classpath);
    command.add("org.openjdk.jmh.Main");
    command.add(BENCHMARK_CLASS + ".*");
    Collections.addAll(command, "-f", Integer.toString(forks)); // fork count
    Collections.addAll(command, "-wi", "1"); // 1 warmup iteration
    Collections.addAll(command, "-i", "1"); // 1 measurement iteration
    Collections.addAll(command, "-w", "1ms"); // warmup duration
    Collections.addAll(command, "-r", "1ms"); // measurement duration
    Collections.addAll(command, "-jvmArgs", "-Djmh.ignoreLock=true");

    ProcessBuilder processBuilder = new ProcessBuilder(command);
    processBuilder.environment().put("DD_API_KEY", "01234567890abcdef123456789ABCDEF");
    return runProcess(processBuilder, "jmh");
  }

  private int runProcess(ProcessBuilder processBuilder, String name) throws Exception {
    processBuilder.directory(projectHome.toFile());
    processBuilder.redirectErrorStream(true);
    Process p = processBuilder.start();

    final InputStream stdout = p.getInputStream();
    Thread outputConsumer =
        new Thread() {
          @Override
          public void run() {
            try {
              byte[] buf = new byte[1024];
              int n;
              while ((n = stdout.read(buf)) != -1) {
                System.out.write(buf, 0, n);
              }
            } catch (Exception ignored) {
            }
          }
        };
    outputConsumer.setDaemon(true);
    outputConsumer.start();

    if (!p.waitFor(PROCESS_TIMEOUT_SECS, TimeUnit.SECONDS)) {
      p.destroyForcibly();
      throw new TimeoutException(name + " process timed out after " + PROCESS_TIMEOUT_SECS + "s");
    }
    return p.exitValue();
  }

  private static List<String> findJavaFiles(Path directory) throws IOException {
    List<String> javaFiles = new ArrayList<>();
    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
            if (file.toString().endsWith(".java")) {
              javaFiles.add(file.toString());
            }
            return FileVisitResult.CONTINUE;
          }
        });
    return javaFiles;
  }

  private static void copyFolder(Path src, Path dest) throws IOException {
    Files.walkFileTree(
        src,
        new SimpleFileVisitor<Path>() {
          @Override
          public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
              throws IOException {
            Files.createDirectories(dest.resolve(src.relativize(dir)));
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.copy(file, dest.resolve(src.relativize(file)));
            return FileVisitResult.CONTINUE;
          }
        });
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> findEvent(List<Map<String, Object>> events, String type) {
    return events.stream().filter(e -> type.equals(e.get("type"))).findFirst().orElse(null);
  }

  private static List<Map<String, Object>> ciVisibilityEvents(List<Map<String, Object>> events) {
    List<Map<String, Object>> filtered = new ArrayList<>();
    for (Map<String, Object> event : events) {
      if (CI_VISIBILITY_EVENT_TYPES.contains(event.get("type"))) {
        filtered.add(event);
      }
    }
    return filtered;
  }
}
