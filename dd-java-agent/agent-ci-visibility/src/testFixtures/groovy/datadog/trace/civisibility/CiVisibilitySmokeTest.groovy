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
}
