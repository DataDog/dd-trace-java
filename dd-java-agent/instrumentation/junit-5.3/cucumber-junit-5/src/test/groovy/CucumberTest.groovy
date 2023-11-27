import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.DDTags
import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.CIConstants
import datadog.trace.api.civisibility.config.SkippableTest
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.CiVisibilityTest
import datadog.trace.civisibility.MockCoverageProbeStore
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
import io.cucumber.core.api.TypeRegistry
import io.cucumber.core.options.Constants
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.EngineFilter
import org.junit.platform.launcher.Launcher
import org.junit.platform.launcher.LauncherDiscoveryRequest
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClasspathResource

@DisableTestTrace(reason = "avoid self-tracing")
class CucumberTest extends CiVisibilityTest {

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
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic", "Addition", CIConstants.TEST_PASS, null, null, false, ["foo"])
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
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic With Examples", "Many additions.Double digits.Example" + getOutlineTestNameSuffix(2, 1), CIConstants.TEST_PASS, null, null, false, ["foo"])
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic With Examples", "Many additions.Double digits.Example" + getOutlineTestNameSuffix(2, 2), CIConstants.TEST_PASS, null, null, false, ["foo"])
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic With Examples", "Many additions.Single digits.Example" + getOutlineTestNameSuffix(1, 1), CIConstants.TEST_PASS, null, null, false, ["foo"])
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic With Examples", "Many additions.Single digits.Example" + getOutlineTestNameSuffix(1, 2), CIConstants.TEST_PASS, null, null, false, ["foo"])
      }
    })
  }

  /**
   * Later Cucumber versions report different example execution names
   */
  private String getOutlineTestNameSuffix(int exampleIdx, int rowIdx) {
    return expectedTestFrameworkVersion() > "7" ? " #${exampleIdx}.${rowIdx}" : " #${rowIdx}"
  }

  def "test skipped generates spans"() {
    setup:
    runFeatures("org/example/cucumber/calculator/basic_arithmetic_skipped.feature")

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testFeatureSpan(it, 2, testSessionId, testModuleId, "Basic Arithmetic", CIConstants.TEST_PASS)
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic", "Addition", CIConstants.TEST_SKIP, [(Tags.TEST_SKIP_REASON): "'cucumber.filter.tags=not ( @Disabled )' did not match this scenario"], null, false, ["foo", "Disabled"])
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic", "Subtraction", CIConstants.TEST_PASS, null, null, false, ["foo"])
      }
    })
  }

  def "test skipped feature generates spans"() {
    setup:
    runFeatures("org/example/cucumber/calculator/basic_arithmetic_skipped_feature.feature")

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_SKIP)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_SKIP)
        testSuiteId = testFeatureSpan(it, 2, testSessionId, testModuleId, "Basic Arithmetic", CIConstants.TEST_SKIP)
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic", "Addition", CIConstants.TEST_SKIP, [(Tags.TEST_SKIP_REASON): "'cucumber.filter.tags=not ( @Disabled )' did not match this scenario"], null, false, ["foo", "Disabled"])
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic", "Subtraction", CIConstants.TEST_SKIP, [(Tags.TEST_SKIP_REASON): "'cucumber.filter.tags=not ( @Disabled )' did not match this scenario"], null, false, ["foo", "Disabled"])
      }
    })
  }

  def "test skipped scenario outline generates spans"() {
    setup:
    runFeatures("org/example/cucumber/calculator/basic_arithmetic_with_examples_skipped.feature")

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 5, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_SKIP)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_SKIP)
        testSuiteId = testFeatureSpan(it, 2, testSessionId, testModuleId, "Basic Arithmetic With Examples", CIConstants.TEST_SKIP)
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic With Examples", "Many additions.Double digits.Example" + getOutlineTestNameSuffix(2, 1), CIConstants.TEST_SKIP, [(Tags.TEST_SKIP_REASON): "'cucumber.filter.tags=not ( @Disabled )' did not match this scenario"], null, false, ["foo", "Disabled"])
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic With Examples", "Many additions.Double digits.Example" + getOutlineTestNameSuffix(2, 2), CIConstants.TEST_SKIP, [(Tags.TEST_SKIP_REASON): "'cucumber.filter.tags=not ( @Disabled )' did not match this scenario"], null, false, ["foo", "Disabled"])
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic With Examples", "Many additions.Single digits.Example" + getOutlineTestNameSuffix(1, 1), CIConstants.TEST_SKIP, [(Tags.TEST_SKIP_REASON): "'cucumber.filter.tags=not ( @Disabled )' did not match this scenario"], null, false, ["foo", "Disabled"])
      }
      trace(1) {
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic With Examples", "Many additions.Single digits.Example" + getOutlineTestNameSuffix(1, 2), CIConstants.TEST_SKIP, [(Tags.TEST_SKIP_REASON): "'cucumber.filter.tags=not ( @Disabled )' did not match this scenario"], null, false, ["foo", "Disabled"])
      }
    })
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
        testScenarioSpan(it, 0, testSessionId, testModuleId, testSuiteId, "Basic Arithmetic", "Addition", CIConstants.TEST_SKIP, testTags, null, false, ["foo"])
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

  protected void runFeatures(String... classpathFeatures) {
    TestEventsHandlerHolder.start()

    DiscoverySelector[] selectors = new DiscoverySelector[classpathFeatures.length]
    for (i in 0..<classpathFeatures.length) {
      selectors[i] = selectClasspathResource(classpathFeatures[i])
    }

    LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
      .filters(EngineFilter.includeEngines("cucumber"))
      .configurationParameter(Constants.GLUE_PROPERTY_NAME, "org.example.cucumber.calculator")
      .configurationParameter(Constants.FILTER_TAGS_PROPERTY_NAME, "not @Disabled")
      .selectors(selectors)
      .build()

    Launcher launcher = LauncherFactory.create()
    launcher.execute(request)

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
    return "junit"
  }

  @Override
  String expectedTestFramework() {
    return "cucumber"
  }

  @Override
  String expectedTestFrameworkVersion() {
    def version = TypeRegistry.package.getImplementationVersion()
    return version != null ? version : "5.4.0" // older releases do not have package version populated
  }

  @Override
  String component() {
    return "junit"
  }
}
