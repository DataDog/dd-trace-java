package datadog.trace.civisibility

import datadog.trace.api.civisibility.config.TestFQN
import spock.lang.Specification

abstract class CiVisibilitySmokeTest extends Specification {
  static final List<String> SMOKE_IGNORED_TAGS = ["content.meta.['_dd.integration']"]

  protected verifyEventsAndCoverages(String projectName, String toolchain, String toolchainVersion, List<Map<String, Object>> events, List<Map<String, Object>> coverages, List<String> additionalDynamicTags = []) {
    def additionalReplacements = ["content.meta.['test.toolchain']": "$toolchain:$toolchainVersion"]

    if (System.getenv().get("GENERATE_TEST_FIXTURES") != null) {
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

  private static verifySnapshots(List<Map<String, Object>> logs, expectedCount) {
    assert logs.size() == expectedCount

    def requiredLogFields = ["logger.name", "logger.method", "dd.spanid", "dd.traceid"]
    def requiredSnapshotFields = ["captures", "exceptionId", "probe", "stack"]

    logs.each { log ->
      assert log.product == "test_optimization"
      requiredLogFields.each { field -> log.containsKey(field) }

      Map<String, Object> debuggerMap = log.debugger as Map<String, Object>
      Map<String, Object> snapshotContent = debuggerMap.snapshot as Map<String, Object>

      assert snapshotContent != null
      requiredSnapshotFields.each { field -> snapshotContent.containsKey(field) }
    }
  }
}
