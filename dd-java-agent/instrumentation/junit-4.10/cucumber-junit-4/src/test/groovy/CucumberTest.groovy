import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.DDTags
import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.CIConstants
import datadog.trace.api.civisibility.config.SkippableTest
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.CiVisibilityTest
import datadog.trace.civisibility.MockCoverageProbeStore
import datadog.trace.instrumentation.junit4.CucumberTracingListener
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder
import io.cucumber.core.options.Constants
import org.example.cucumber.TestSucceedCucumber
import org.junit.runner.JUnitCore

import java.util.stream.Collectors

@DisableTestTrace(reason = "avoid self-tracing")
class CucumberTest extends CiVisibilityTest {

  def runner = new JUnitCore()

  def "test success generates spans"() {
    setup:
    runFeatures("org/example/cucumber/calculator/basic_arithmetic.feature")

    expect:
    expectFeaturesCoverage("org/example/cucumber/calculator/basic_arithmetic.feature")

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
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic", "Addition", CIConstants.TEST_PASS,
          null, null, false, ["foo"])
      }
    })
  }

  def "test scenario outline generates spans"() {
    setup:
    runFeatures("org/example/cucumber/calculator/basic_arithmetic_with_examples.feature")

    expect:
    // each example is a separate test case
    expectFeaturesCoverage(
      "org/example/cucumber/calculator/basic_arithmetic_with_examples.feature",
      "org/example/cucumber/calculator/basic_arithmetic_with_examples.feature",
      "org/example/cucumber/calculator/basic_arithmetic_with_examples.feature",
      "org/example/cucumber/calculator/basic_arithmetic_with_examples.feature"
      )

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
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic With Examples", "Many additions" + getOutlineTestNameSuffix(1), CIConstants.TEST_PASS,
          null, null, false, ["foo"])
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic With Examples", "Many additions" + getOutlineTestNameSuffix(2), CIConstants.TEST_PASS,
          null, null, false, ["foo"])
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic With Examples", "Many additions" + getOutlineTestNameSuffix(3), CIConstants.TEST_PASS,
          null, null, false, ["foo"])
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic With Examples", "Many additions" + getOutlineTestNameSuffix(4), CIConstants.TEST_PASS,
          null, null, false, ["foo"])
      }
    })
  }

  /**
   * Later Cucumber versions add a suffix to outline test execution names
   */
  private String getOutlineTestNameSuffix(int idx) {
    return expectedTestFrameworkVersion() > "7" ? " #${idx}" : ""
  }

  def "test failure generates spans"() {
    setup:
    runFeatures("org/example/cucumber/calculator/basic_arithmetic_failed.feature")

    expect:
    expectFeaturesCoverage("org/example/cucumber/calculator/basic_arithmetic_failed.feature")

    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_FAIL)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_FAIL)
        testSuiteId = testFeatureSpan(it, 2, testSessionId, testModuleId, "Basic Arithmetic", CIConstants.TEST_FAIL)
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic", "Addition", CIConstants.TEST_FAIL,
          null, exception, false, ["foo"])
      }
    })

    where:
    exception = new AssertionError("expected:<42.0> but was:<9.0>")
  }

  def "test ITR test skipping"() {
    setup:
    givenSkippableTests([new SkippableTest("Basic Arithmetic", "Addition", null, null),])

    runFeatures("org/example/cucumber/calculator/basic_arithmetic.feature")

    expect:
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
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic", "Addition", CIConstants.TEST_SKIP,
          testTags, null, false , ["foo"])
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Skipped by Datadog Intelligent Test Runner", (Tags.TEST_SKIPPED_BY_ITR): true]
  }

  def "test ITR test unskippable"() {
    setup:
    givenSkippableTests([new SkippableTest("Basic Arithmetic", "Addition", null, null),])

    runFeatures("org/example/cucumber/calculator/basic_arithmetic_unskippable.feature")

    expect:
    expectFeaturesCoverage("org/example/cucumber/calculator/basic_arithmetic_unskippable.feature")

    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS, [
          (Tags.TEST_ITR_TESTS_SKIPPING_ENABLED): true,
          (Tags.TEST_ITR_TESTS_SKIPPING_TYPE): "test",
          (Tags.TEST_ITR_TESTS_SKIPPING_COUNT): 0,
        ])
        testSuiteId = testFeatureSpan(it, 2, testSessionId, testModuleId, "Basic Arithmetic", CIConstants.TEST_PASS)
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic", "Addition", CIConstants.TEST_PASS, testTags, null, false, ["datadog_itr_unskippable"])
      }
    })

    where:
    testTags = [(Tags.TEST_ITR_UNSKIPPABLE): true, (Tags.TEST_ITR_FORCED_RUN): true]
  }

  def "test ITR test unskippable suite"() {
    setup:
    givenSkippableTests([new SkippableTest("Basic Arithmetic", "Addition", null, null),])

    runFeatures("org/example/cucumber/calculator/basic_arithmetic_unskippable_suite.feature")

    expect:
    expectFeaturesCoverage("org/example/cucumber/calculator/basic_arithmetic_unskippable_suite.feature")

    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS, [
          (Tags.TEST_ITR_TESTS_SKIPPING_ENABLED): true,
          (Tags.TEST_ITR_TESTS_SKIPPING_TYPE): "test",
          (Tags.TEST_ITR_TESTS_SKIPPING_COUNT): 0,
        ])
        testSuiteId = testFeatureSpan(it, 2, testSessionId, testModuleId, "Basic Arithmetic", CIConstants.TEST_PASS)
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic", "Addition", CIConstants.TEST_PASS, testTags, null, false, ["datadog_itr_unskippable"])
      }
    })

    where:
    testTags = [(Tags.TEST_ITR_UNSKIPPABLE): true, (Tags.TEST_ITR_FORCED_RUN): true]
  }

  private void runFeatures(String... classpathFeatures) {
    System.setProperty(Constants.GLUE_PROPERTY_NAME, "org.example.cucumber.calculator")
    System.setProperty(Constants.FILTER_TAGS_PROPERTY_NAME, "not @Disabled")
    System.setProperty(Constants.FEATURES_PROPERTY_NAME, Arrays.asList(classpathFeatures).stream()
    .map(f -> "classpath:" + f).
    collect(Collectors.joining(",")))

    TestEventsHandlerHolder.start()
    runner.run(TestSucceedCucumber)
    TestEventsHandlerHolder.stop()
  }

  private static void expectFeaturesCoverage(String... classpathFeatures) {
    def coveredResources = MockCoverageProbeStore.INSTANCE.getNonCodeResources()
    if (coveredResources.size() != classpathFeatures.length) {
      throw new AssertionError("Expected " + Arrays.asList(classpathFeatures) + " covered features, got " + coveredResources)
    }
    for (String feature : classpathFeatures) {
      if (!coveredResources.contains(feature)) {
        throw new AssertionError("Expected " + Arrays.asList(classpathFeatures) + " covered features, got " + coveredResources)
      }
    }
  }

  @Override
  String expectedOperationPrefix() {
    "junit"
  }

  @Override
  String expectedTestFramework() {
    CucumberTracingListener.FRAMEWORK_NAME
  }

  @Override
  String expectedTestFrameworkVersion() {
    CucumberTracingListener.FRAMEWORK_VERSION
  }

  @Override
  String component() {
    "junit"
  }
}
