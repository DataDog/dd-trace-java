package datadog.trace.civisibility;

import static datadog.trace.util.ConfigStrings.propertyNameToSystemPropertyName;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.trace.api.Config;
import datadog.trace.api.civisibility.config.TestFQN;
import datadog.trace.api.config.CiVisibilityConfig;
import datadog.trace.api.config.GeneralConfig;
import datadog.trace.api.config.TraceInstrumentationConfig;
import datadog.trace.api.config.TracerConfig;
import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.io.TempDir;

public abstract class CiVisibilitySmokeTest {

  public static final List<String> SMOKE_IGNORED_TAGS =
      Collections.unmodifiableList(
          Arrays.asList("content.meta.['_dd.integration']", "content.meta.['_dd.svc_src']"));

  protected static final String AGENT_JAR =
      System.getProperty("datadog.smoketest.agent.shadowJar.path");
  protected static final String TEST_ENVIRONMENT_NAME = "integration-test";
  protected static final String JAVAC_PLUGIN_VERSION =
      Config.get().getCiVisibilityCompilerPluginVersion();
  protected static final String JACOCO_PLUGIN_VERSION =
      Config.get().getCiVisibilityJacocoPluginVersion();

  private static final Map<String, String> DEFAULT_TRACER_CONFIG = defaultJvmArguments();

  @TempDir protected Path prefsDir;

  protected static String buildJavaHome() {
    String javaHome = System.getProperty("java.home");
    File javacPath = Paths.get(javaHome, "bin", "javac").toFile();
    if (javacPath.exists()) {
      return javaHome;
    }
    // In CI for JDK 8, java.home may point to the JRE directory (e.g., /usr/lib/jvm/8/jre).
    // The JDK with javac is in the parent directory.
    File parentDir = new File(javaHome).getParentFile();
    File parentJavacPath = new File(parentDir, Paths.get("bin", "javac").toString());
    if (parentJavacPath.exists()) {
      return parentDir.getAbsolutePath();
    }
    // Fallback to java.home and let callers handle the error if javac is not found.
    return javaHome;
  }

  protected static String javaPath() {
    String separator = System.getProperty("file.separator");
    return buildJavaHome() + separator + "bin" + separator + "java";
  }

  protected static String javacPath() {
    String separator = System.getProperty("file.separator");
    return buildJavaHome() + separator + "bin" + separator + "javac";
  }

  private static Map<String, String> defaultJvmArguments() {
    Map<String, String> argMap = new HashMap<>();
    argMap.put(GeneralConfig.ENV, TEST_ENVIRONMENT_NAME);
    argMap.put(CiVisibilityConfig.CIVISIBILITY_ENABLED, "true");
    argMap.put(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED, "true");
    argMap.put(CiVisibilityConfig.CIVISIBILITY_CIPROVIDER_INTEGRATION_ENABLED, "false");
    argMap.put(CiVisibilityConfig.CIVISIBILITY_GIT_UPLOAD_ENABLED, "false");
    argMap.put(CiVisibilityConfig.CIVISIBILITY_GIT_CLIENT_ENABLED, "false");
    argMap.put(CiVisibilityConfig.CIVISIBILITY_FLAKY_RETRY_ONLY_KNOWN_FLAKES, "true");
    argMap.put(CiVisibilityConfig.CIVISIBILITY_COMPILER_PLUGIN_VERSION, JAVAC_PLUGIN_VERSION);
    argMap.put(TraceInstrumentationConfig.CODE_ORIGIN_FOR_SPANS_ENABLED, "false");
    return argMap;
  }

  private static Map<String, String> buildJvmArgMap(
      String mockBackendIntakeUrl, String serviceName, Map<String, String> additionalArgs) {
    Map<String, String> argMap = new HashMap<>(DEFAULT_TRACER_CONFIG);
    argMap.put(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_URL, mockBackendIntakeUrl);
    argMap.put(CiVisibilityConfig.CIVISIBILITY_INTAKE_AGENTLESS_URL, mockBackendIntakeUrl);
    argMap.put(TracerConfig.TRACE_AGENT_URL, mockBackendIntakeUrl);
    argMap.putAll(additionalArgs);

    if (serviceName != null) {
      argMap.put(GeneralConfig.SERVICE_NAME, serviceName);
    }

    return argMap;
  }

  protected List<String> buildJvmArguments(
      String mockBackendIntakeUrl, String serviceName, Map<String, String> additionalArgs) {
    List<String> arguments = new ArrayList<>(Arrays.asList("-Xms256m", "-Xmx512m"));

    arguments.add(preventJulPrefsFileLock());

    Map<String, String> argMap = buildJvmArgMap(mockBackendIntakeUrl, serviceName, additionalArgs);

    // Convenience switches for local debugging. Set as JVM system properties (e.g. via
    // `-Ddatadog.civisibility.smoketest.debug.parent=1`) rather than env vars, to keep the
    // config-inversion-linter happy (it forbids unregistered `DD_…` env-var literals in
    // `src/main/java`) and to avoid `System.getenv` in main sources.
    if (System.getProperty("datadog.civisibility.smoketest.debug.parent") != null) {
      arguments.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005");
    }
    if (System.getProperty("datadog.civisibility.smoketest.debug.child") != null) {
      argMap.put(CiVisibilityConfig.CIVISIBILITY_DEBUG_PORT, "5055");
    }

    // CI-Vis smoke tests produce a lot of logs in debug mode, so it is disabled by default.
    // Note: GitLab capacity for job logs at 32 MB and truncates the rest, which can fail the job.
    // Enable full debug via `-Ddatadog.civisibility.smoketest.debug.enabled=true`.
    if (System.getProperty("datadog.civisibility.smoketest.debug.enabled") != null) {
      argMap.put(GeneralConfig.TRACE_DEBUG, "true");
    }

    String agentArgs =
        argMap.entrySet().stream()
            .map(e -> propertyNameToSystemPropertyName(e.getKey()) + "=" + e.getValue())
            .collect(Collectors.joining(","));
    arguments.add("-javaagent:" + AGENT_JAR + "=" + agentArgs);

    return arguments;
  }

  /**
   * Trick to prevent jul Prefs file lock issue on forked processes, in particular in CI which runs
   * on Linux and have competing processes trying to write to it, including the Gradle daemon.
   *
   * <pre>{@code
   * Couldn't flush user prefs: java.util.prefs.BackingStoreException: Couldn't get file lock.
   * }</pre>
   *
   * Note, some tests can setup arguments on spec level, so {@code prefsDir} will be {@code null}
   * during {@code @BeforeAll}.
   */
  protected String preventJulPrefsFileLock() {
    Path resolved = prefsDir != null ? prefsDir : tempUserPrefsPath();
    return "-Djava.util.prefs.userRoot=" + resolved.toAbsolutePath();
  }

  private static Path tempUserPrefsPath() {
    String uniqueId =
        System.currentTimeMillis() + "_" + System.nanoTime() + "_" + Thread.currentThread().getId();
    return Paths.get(System.getProperty("java.io.tmpdir"), "gradle-test-userPrefs", uniqueId);
  }

  protected void verifyEventsAndCoverages(
      String projectName,
      String toolchain,
      String toolchainVersion,
      List<? extends Map<?, ?>> events,
      List<? extends Map<?, ?>> coverages) {
    verifyEventsAndCoverages(
        projectName, toolchain, toolchainVersion, events, coverages, Collections.emptyList());
  }

  protected void verifyEventsAndCoverages(
      String projectName,
      String toolchain,
      String toolchainVersion,
      List<? extends Map<?, ?>> events,
      List<? extends Map<?, ?>> coverages,
      List<String> additionalDynamicTags) {
    Map<String, String> additionalReplacements = new HashMap<>();
    additionalReplacements.put(
        "content.meta.['test.toolchain']", toolchain + ":" + toolchainVersion);

    if (System.getenv("GENERATE_TEST_FIXTURES") != null) {
      String baseTemplatesPath;
      try {
        baseTemplatesPath =
            CiVisibilitySmokeTest.class
                .getClassLoader()
                .getResource(projectName)
                .toURI()
                .getSchemeSpecificPart()
                .replace("build/resources/test", "src/test/resources");
      } catch (Exception e) {
        throw new RuntimeException(e);
      }
      List<String> dynamicPaths = new ArrayList<>(additionalReplacements.keySet());
      dynamicPaths.addAll(additionalDynamicTags);
      CiVisibilityTestUtils.generateTemplates(
          baseTemplatesPath, events, coverages, dynamicPaths, SMOKE_IGNORED_TAGS);
    } else {
      CiVisibilityTestUtils.assertData(
          projectName,
          events,
          coverages,
          additionalReplacements,
          SMOKE_IGNORED_TAGS,
          additionalDynamicTags);
    }
  }

  protected TestFQN test(String suiteName, String testName) {
    return new TestFQN(suiteName, testName);
  }

  protected void verifyTestOrder(List<? extends Map<?, ?>> events, List<TestFQN> expectedOrder) {
    CiVisibilityTestUtils.assertTestsOrder(events, expectedOrder);
  }

  /**
   * This is a basic sanity check for telemetry metrics. It only checks that the reported number of
   * events created and finished is as expected.
   *
   * <p>Currently the check is not performed for Gradle builds: Gradle daemon started with Gradle
   * TestKit outlives the test, so the final telemetry flush happens after the assertions.
   */
  protected void verifyTelemetryMetrics(
      List<Map<String, Object>> receivedTelemetryMetrics,
      List<Map<String, Object>> receivedTelemetryDistributions,
      int expectedEventsCount) {
    int eventsCreated = 0;
    int eventsFinished = 0;
    for (Map<String, Object> metric : receivedTelemetryMetrics) {
      if ("event_created".equals(metric.get("metric"))) {
        for (Object point : (List<?>) metric.get("points")) {
          eventsCreated += ((Number) ((List<?>) point).get(1)).intValue();
        }
      }
      if ("event_finished".equals(metric.get("metric"))) {
        for (Object point : (List<?>) metric.get("points")) {
          eventsFinished += ((Number) ((List<?>) point).get(1)).intValue();
        }
      }
    }
    assertEquals(expectedEventsCount, eventsCreated);
    assertEquals(expectedEventsCount, eventsFinished);

    // an even more basic smoke check for distributions: assert that we received some
    assertFalse(receivedTelemetryDistributions.isEmpty());
  }

  protected void verifyCoverageReports(
      String projectName,
      List<CiVisibilityTestUtils.CoverageReport> reports,
      Map<String, String> replacements) {
    CiVisibilityTestUtils.assertData(projectName, reports, replacements);
  }

  protected static void verifySnapshotLogs(
      List<Map<String, Object>> receivedLogs, int expectedProbes, int expectedSnapshots) {
    int logsPerProbe = 3; // 3 probe statuses per probe -> received, installed, emitting

    assertEquals(logsPerProbe * expectedProbes + expectedSnapshots, receivedLogs.size());

    List<Map<String, Object>> probeStatusLogs = new ArrayList<>();
    List<Map<String, Object>> snapshotLogs = new ArrayList<>();
    for (Map<String, Object> log : receivedLogs) {
      if (log.containsKey("message")) {
        probeStatusLogs.add(log);
      } else {
        snapshotLogs.add(log);
      }
    }

    verifyProbeStatuses(probeStatusLogs, expectedProbes);
    verifySnapshots(snapshotLogs, expectedSnapshots);
  }

  private static void verifyProbeStatuses(List<Map<String, Object>> logs, int expectedCount) {
    long received =
        logs.stream()
            .filter(log -> ((String) log.get("message")).startsWith("Received probe"))
            .count();
    long installed =
        logs.stream()
            .filter(log -> ((String) log.get("message")).startsWith("Installed probe"))
            .count();
    long emitting =
        logs.stream().filter(log -> ((String) log.get("message")).endsWith("is emitting.")).count();
    assertEquals(expectedCount, received);
    assertEquals(expectedCount, installed);
    assertEquals(expectedCount, emitting);
  }

  protected static void verifySnapshots(List<Map<String, Object>> logs, int expectedCount) {
    assertEquals(expectedCount, logs.size());

    List<String> requiredLogFields =
        Arrays.asList("logger.name", "logger.method", "dd.span_id", "dd.trace_id");
    List<String> requiredSnapshotFields =
        Arrays.asList("captures", "exceptionId", "probe", "stack");

    for (Map<String, Object> log : logs) {
      requiredLogFields.forEach(
          field -> assertTrue(log.containsKey(field), "log must contain field: " + field));

      @SuppressWarnings("unchecked")
      Map<String, Object> debuggerMap = (Map<String, Object>) log.get("debugger");
      @SuppressWarnings("unchecked")
      Map<String, Object> snapshotContent = (Map<String, Object>) debuggerMap.get("snapshot");

      assertNotNull(snapshotContent, "snapshot must not be null");
      requiredSnapshotFields.forEach(
          field ->
              assertTrue(
                  snapshotContent.containsKey(field), "snapshot must contain field: " + field));
    }
  }
}
