package datadog.trace.civisibility

import datadog.environment.EnvironmentVariables
import datadog.trace.api.Config
import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.api.config.GeneralConfig
import spock.lang.Specification
import spock.util.environment.Jvm

import static datadog.trace.util.ConfigStrings.propertyNameToSystemPropertyName

abstract class CiVisibilitySmokeTest extends Specification {
  static final List<String> SMOKE_IGNORED_TAGS = ["content.meta.['_dd.integration']"]

  protected static final String AGENT_JAR = System.getProperty("datadog.smoketest.agent.shadowJar.path")
  protected static final String TEST_ENVIRONMENT_NAME = "integration-test"
  protected static final String JAVAC_PLUGIN_VERSION = Config.get().ciVisibilityCompilerPluginVersion
  protected static final String JACOCO_PLUGIN_VERSION = Config.get().ciVisibilityJacocoPluginVersion

  private static final Map<String,String> DEFAULT_TRACER_CONFIG = defaultJvmArguments()

  protected static String buildJavaHome() {
    if (Jvm.current.isJava8()) {
      return EnvironmentVariables.get("JAVA_8_HOME")
    }
    return EnvironmentVariables.get("JAVA_" + Jvm.current.getJavaSpecificationVersion() + "_HOME")
  }

  protected static String javaPath() {
    final String separator = System.getProperty("file.separator")
    return "${buildJavaHome()}${separator}bin${separator}java"
  }

  protected static String javacPath() {
    final String separator = System.getProperty("file.separator")
    return "${buildJavaHome()}${separator}bin${separator}javac"
  }

  private static Map<String, String> defaultJvmArguments() {
    Map<String, String> argMap = new HashMap<>()
    argMap.put(GeneralConfig.TRACE_DEBUG, "true")
    argMap.put(GeneralConfig.ENV, TEST_ENVIRONMENT_NAME)
    argMap.put(CiVisibilityConfig.CIVISIBILITY_ENABLED, "true")
    argMap.put(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_ENABLED, "true")
    argMap.put(CiVisibilityConfig.CIVISIBILITY_CIPROVIDER_INTEGRATION_ENABLED, "false")
    argMap.put(CiVisibilityConfig.CIVISIBILITY_GIT_UPLOAD_ENABLED, "false")
    argMap.put(CiVisibilityConfig.CIVISIBILITY_FLAKY_RETRY_ONLY_KNOWN_FLAKES, "true")
    argMap.put(CiVisibilityConfig.CIVISIBILITY_COMPILER_PLUGIN_VERSION, JAVAC_PLUGIN_VERSION)
    return argMap
  }

  private static Map<String, String> buildJvmArgMap(String mockBackendIntakeUrl, String serviceName, Map<String, String> additionalArgs) {
    Map<String, String> argMap = new HashMap<>(DEFAULT_TRACER_CONFIG)
    argMap.put(CiVisibilityConfig.CIVISIBILITY_AGENTLESS_URL, mockBackendIntakeUrl)
    argMap.put(CiVisibilityConfig.CIVISIBILITY_INTAKE_AGENTLESS_URL, mockBackendIntakeUrl)
    argMap.putAll(additionalArgs)

    if (serviceName != null) {
      argMap.put(GeneralConfig.SERVICE_NAME, serviceName)
    }

    return argMap
  }

  protected List<String> buildJvmArguments(String mockBackendIntakeUrl, String serviceName, Map<String, String> additionalArgs) {
    List<String> arguments = []
    Map<String, String> argMap = buildJvmArgMap(mockBackendIntakeUrl, serviceName, additionalArgs)

    // for convenience when debugging locally
    if (EnvironmentVariables.get("DD_CIVISIBILITY_SMOKETEST_DEBUG_PARENT") != null) {
      arguments +=  "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=5005"
    }
    if (EnvironmentVariables.get("DD_CIVISIBILITY_SMOKETEST_DEBUG_CHILD") != null) {
      argMap.put(CiVisibilityConfig.CIVISIBILITY_DEBUG_PORT, "5055")
    }

    String agentArgs = argMap.collect { k, v -> "${propertyNameToSystemPropertyName(k)}=${v}" }.join(",")
    arguments += "-javaagent:${AGENT_JAR}=${agentArgs}".toString()

    return arguments
  }

  protected verifyEventsAndCoverages(String projectName, String toolchain, String toolchainVersion, List<Map<String, Object>> events, List<Map<String, Object>> coverages, List<String> additionalDynamicTags = []) {
    def additionalReplacements = ["content.meta.['test.toolchain']": "$toolchain:$toolchainVersion"]

    if (EnvironmentVariables.get("GENERATE_TEST_FIXTURES") != null) {
      def baseTemplatesPath = CiVisibilitySmokeTest.classLoader.getResource(projectName).toURI().schemeSpecificPart.replace('build/resources/test', 'src/test/resources')
      CiVisibilityTestUtils.generateTemplates(baseTemplatesPath, events, coverages, additionalReplacements.keySet() + additionalDynamicTags, SMOKE_IGNORED_TAGS)
    } else {
      CiVisibilityTestUtils.assertData(projectName, events, coverages, additionalReplacements, SMOKE_IGNORED_TAGS, additionalDynamicTags)
    }
  }

  protected test(String suiteName, String testName) {
    return new TestFQN(suiteName, testName)
  }

  protected verifyTestOrder(List<Map<String, Object>> events, List<TestFQN> expectedOrder) {
    CiVisibilityTestUtils.assertTestsOrder(events, expectedOrder)
  }

  /**
   * This is a basic sanity check for telemetry metrics.
   * It only checks that the reported number of events created and finished is as expected.
   * <p>
   * Currently the check is not performed for Gradle builds:
   * Gradle daemon started with Gradle TestKit outlives the test, so the final telemetry flush happens after the assertions.
   */
  protected verifyTelemetryMetrics(List<Map<String, Object>> receivedTelemetryMetrics, List<Map<String, Object>> receivedTelemetryDistributions, int expectedEventsCount) {
    int eventsCreated = 0, eventsFinished = 0
    for (Map<String, Object> metric : receivedTelemetryMetrics) {
      if (metric["metric"] == "event_created") {
        for (def point : metric["points"]) {
          eventsCreated += point[1]
        }
      }
      if (metric["metric"] == "event_finished") {
        for (def point : metric["points"]) {
          eventsFinished += point[1]
        }
      }
    }
    assert eventsCreated == expectedEventsCount
    assert eventsFinished == expectedEventsCount

    // an even more basic smoke check for distributions: assert that we received some
    assert !receivedTelemetryDistributions.isEmpty()
  }

  protected verifyCoverageReports(String projectName, List<CiVisibilityTestUtils.CoverageReport> reports, Map<String, String> replacements) {
    CiVisibilityTestUtils.assertData(projectName, reports, replacements)
  }

  protected static verifySnapshotLogs(List<Map<String, Object>> receivedLogs, int expectedProbes, int expectedSnapshots) {
    def logsPerProbe = 3 // 3 probe statuses per probe -> received, installed, emitting

    assert receivedLogs.size() == logsPerProbe * expectedProbes + expectedSnapshots

    def probeStatusLogs = receivedLogs.findAll { it.containsKey("message") }
    def snapshotLogs = receivedLogs.findAll { !it.containsKey("message") }

    verifyProbeStatuses(probeStatusLogs, expectedProbes)
    verifySnapshots(snapshotLogs, expectedSnapshots)
  }

  private static verifyProbeStatuses(List<Map<String, Object>> logs, int expectedCount) {
    assert logs.findAll { log -> ((String) log.message).startsWith("Received probe") }.size() == expectedCount
    assert logs.findAll { log -> ((String) log.message).startsWith("Installed probe") }.size() == expectedCount
    assert logs.findAll { log -> ((String) log.message).endsWith("is emitting.") }.size() == expectedCount
  }

  protected static verifySnapshots(List<Map<String, Object>> logs, expectedCount) {
    assert logs.size() == expectedCount

    def requiredLogFields = ["logger.name", "logger.method", "dd.spanid", "dd.traceid"]
    def requiredSnapshotFields = ["captures", "exceptionId", "probe", "stack"]

    logs.each { log ->
      requiredLogFields.each { field -> log.containsKey(field) }

      Map<String, Object> debuggerMap = log.debugger as Map<String, Object>
      Map<String, Object> snapshotContent = debuggerMap.snapshot as Map<String, Object>

      assert snapshotContent != null
      requiredSnapshotFields.each { field -> snapshotContent.containsKey(field) }
    }
  }
}
