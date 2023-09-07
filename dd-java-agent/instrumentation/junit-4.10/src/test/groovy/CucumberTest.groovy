import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.test.asserts.TraceAssert
import datadog.trace.api.DDTags
import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.CIConstants
import datadog.trace.api.civisibility.config.SkippableTest
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.CiVisibilityTest
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder
import org.example.TestSucceedCucumber
import org.example.TestSucceedExamplesCucumber
import org.junit.runner.JUnitCore

@DisableTestTrace(reason = "avoid self-tracing")
class CucumberTest extends CiVisibilityTest {

  def runner = new JUnitCore()

  def "test success generates spans"() {
    setup:
    runTestClasses(TestSucceedCucumber)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testFeatureSpan(it, 2, testSessionId, testModuleId, "Basic Arithmetic", CIConstants.TEST_PASS)
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic", "Addition", CIConstants.TEST_PASS)
      }
    })
  }

  def "test scenario outline generates spans"() {
    setup:
    runTestClasses(TestSucceedExamplesCucumber)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 5, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testFeatureSpan(it, 2, testSessionId, testModuleId, "Basic Arithmetic With Examples", CIConstants.TEST_PASS)
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic With Examples", "Many additions", CIConstants.TEST_PASS)
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic With Examples", "Many additions", CIConstants.TEST_PASS)
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic With Examples", "Many additions", CIConstants.TEST_PASS)
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic With Examples", "Many additions", CIConstants.TEST_PASS)
      }
    })
  }

  def "test ITR test skipping"() {
    setup:
    givenSkippableTests([new SkippableTest("Basic Arithmetic", "Addition", null, null),])
    runTestClasses(TestSucceedCucumber)

    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_SKIP)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_SKIP, [
          (DDTags.CI_ITR_TESTS_SKIPPED): true,
          (Tags.TEST_ITR_TESTS_SKIPPING_ENABLED): true,
          (Tags.TEST_ITR_TESTS_SKIPPING_TYPE): "test",
          (Tags.TEST_ITR_TESTS_SKIPPING_COUNT): 1,
        ])
        testSuiteId = testFeatureSpan(it, 2, testSessionId, testModuleId, "Basic Arithmetic", CIConstants.TEST_SKIP)
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic", "Addition", CIConstants.TEST_SKIP, testTags)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Skipped by Datadog Intelligent Test Runner", (Tags.TEST_SKIPPED_BY_ITR): true]
  }

  private void runTestClasses(Class<?>... tests) {
    TestEventsHandlerHolder.start()
    runner.run(tests)
    TestEventsHandlerHolder.stop()
  }

  Long testFeatureSpan(final TraceAssert trace,
    final int index,
    final Long testSessionId,
    final Long testModuleId,
    final String testSuite,
    final String testStatus,
    final Map<String, Object> testTags = null,
    final Throwable exception = null,
    final boolean emptyDuration = false,
    final Collection<String> categories = null) {
    return testSuiteSpan(trace, index, testSessionId, testModuleId, testSuite, testStatus, testTags, exception, emptyDuration, categories, false)
  }

  void testScenarioSpan(final TraceAssert trace,
    final int index,
    final Long testSessionId,
    final Long testModuleId,
    final Long testSuiteId,
    final String testSuite,
    final String testName,
    final String testStatus,
    final Map<String, Object> testTags = null,
    final Throwable exception = null,
    final boolean emptyDuration = false,
    final Collection<String> categories = null) {
    testSpan(trace, index, testSessionId, testModuleId, testSuiteId, testSuite, testName, null, testStatus, testTags, exception, emptyDuration, categories, false, false)
  }

  @Override
  String expectedOperationPrefix() {
    return "junit"
  }

  @Override
  String expectedTestFramework() {
    return "cucumber"
  }

  @Override
  String expectedTestFrameworkVersion() {
    return "5.4.0"
  }

  @Override
  String component() {
    return "junit"
  }
}
