import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.test.base.TestFrameworkTest
import datadog.trace.api.DisableTestTrace
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.civisibility.TestEventsHandler
import datadog.trace.instrumentation.junit5.JUnit5Decorator
import org.example.TestAssumption
import org.example.TestAssumptionAndSucceed
import org.example.TestAssumptionLegacy
import org.example.TestError
import org.example.TestFactory
import org.example.TestFailed
import org.example.TestFailedAndSucceed
import org.example.TestFailedSuiteSetup
import org.example.TestFailedSuiteTearDown
import org.example.TestInheritance
import org.example.TestParameterized
import org.example.TestRepeated
import org.example.TestSkipped
import org.example.TestSkippedClass
import org.example.TestSkippedNested
import org.example.TestSucceed
import org.example.TestSucceedAndSkipped
import org.example.TestSucceedNested
import org.example.TestSucceedWithCategories
import org.example.TestSuiteSetUpAssumption
import org.example.TestTemplate
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.opentest4j.AssertionFailedError

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit5Test extends TestFrameworkTest {

  def "test success generate spans"() {
    setup:
    runTestClasses(TestSucceed)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestSucceed", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceed", "test_succeed", TestEventsHandler.TEST_PASS)
      }
    })
  }

  def "test inheritance generates spans"() {
    setup:
    runTestClasses(TestInheritance)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestInheritance", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestInheritance", "test_succeed", TestEventsHandler.TEST_PASS)
      }
    })
  }

  def "test parameterized generates spans"() {
    setup:
    runTestClasses(TestParameterized)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestParameterized", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterized", "test_parameterized", TestEventsHandler.TEST_PASS, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterized", "test_parameterized", TestEventsHandler.TEST_PASS, testTags_1)
      }
    })

    where:
    testTags_0 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"[1] 0, 0, 0, some:\\\"parameter\\\""}}']
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"[2] 1, 1, 2, some:\\\"parameter\\\""}}']
  }

  def "test repeated generates spans"() {
    setup:
    runTestClasses(TestRepeated)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestRepeated", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestRepeated", "test_repeated", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestRepeated", "test_repeated", TestEventsHandler.TEST_PASS)
      }
    })
  }

  def "test template generates spans"() {
    setup:
    runTestClasses(TestTemplate)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestTemplate", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestTemplate", "test_template", TestEventsHandler.TEST_PASS, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestTemplate", "test_template", TestEventsHandler.TEST_PASS, testTags_1)
      }
    })

    where:
    testTags_0 = [(Tags.TEST_PARAMETERS): "{\"metadata\":{\"test_name\":\"test_template_1\"}}"]
    testTags_1 = [(Tags.TEST_PARAMETERS): "{\"metadata\":{\"test_name\":\"test_template_2\"}}"]
  }

  def "test factory generates spans"() {
    setup:
    runTestClasses(TestFactory)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestFactory", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFactory", "test_factory", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFactory", "test_factory", TestEventsHandler.TEST_PASS)
      }
    })
  }

  def "test failed generates spans"() {
    setup:
    runTestClassesSuppressingExceptions(TestFailed)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestFailed", TestEventsHandler.TEST_FAIL)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailed", "test_failed", TestEventsHandler.TEST_FAIL, null, exception)
      }
    })

    where:
    exception = new AssertionFailedError("expected: <true> but was: <false>")
  }

  def "test error generates spans"() {
    setup:
    runTestClassesSuppressingExceptions(TestError)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestError", TestEventsHandler.TEST_FAIL)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestError", "test_error", TestEventsHandler.TEST_FAIL, null, exception)
      }
    })

    where:
    exception = new IllegalArgumentException("This exception is an example")
  }

  def "test skipped generates spans"() {
    setup:
    runTestClasses(TestSkipped)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestSkipped", TestEventsHandler.TEST_SKIP)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkipped", "test_skipped", TestEventsHandler.TEST_SKIP, testTags, null, true)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in test"]
  }

  def "test class skipped generated spans"() {
    setup:
    runTestClasses(TestSkippedClass)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 5, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestSkippedClass", TestEventsHandler.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_case_skipped", TestEventsHandler.TEST_SKIP, testTags, null, true)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_factory_skipped", TestEventsHandler.TEST_SKIP, testTags, null, true)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_parameterized_skipped", TestEventsHandler.TEST_SKIP, parameterizedTestTags, null, true)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_repeated_skipped", TestEventsHandler.TEST_SKIP, testTags, null, true)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in class"]
    parameterizedTestTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in class", (Tags.TEST_PARAMETERS): "{\"metadata\":{\"test_name\":\"test_parameterized_skipped(int, int, int, String)\"}}"]
  }

  def "test with failing assumptions generated spans"() {
    setup:
    runTestClasses(TestAssumption)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestAssumption", TestEventsHandler.TEST_SKIP)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestAssumption", "test_fail_assumption", TestEventsHandler.TEST_SKIP, testTags)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Assumption failed: assumption is not true"]
  }

  def "test with failing legacy assumptions generated spans"() {
    setup:
    runTestClasses(TestAssumptionLegacy)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestAssumptionLegacy", TestEventsHandler.TEST_SKIP)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestAssumptionLegacy", "test_fail_assumption_legacy", TestEventsHandler.TEST_SKIP, testTags)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "assumption is not fulfilled"]
  }

  def "test success and skipped generates spans"() {
    setup:
    runTestClasses(TestSucceedAndSkipped)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestSucceedAndSkipped", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedAndSkipped", "test_skipped", TestEventsHandler.TEST_SKIP, testTags, null, true)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedAndSkipped", "test_succeed", TestEventsHandler.TEST_PASS)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in test"]
  }

  def "test success and failure generates spans"() {
    setup:
    runTestClasses(TestFailedAndSucceed)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 4, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestFailedAndSucceed", TestEventsHandler.TEST_FAIL)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_another_succeed", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_failed", TestEventsHandler.TEST_FAIL, null, exception)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_succeed", TestEventsHandler.TEST_PASS)
      }
    })

    where:
    exception = new AssertionFailedError("expected: <true> but was: <false>")
  }

  def "test suite teardown failure generates spans"() {
    setup:
    runTestClasses(TestFailedSuiteTearDown)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestFailedSuiteTearDown", TestEventsHandler.TEST_FAIL, null, exception)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedSuiteTearDown", "test_another_succeed", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedSuiteTearDown", "test_succeed", TestEventsHandler.TEST_PASS)
      }
    })

    where:
    exception = new RuntimeException("suite tear down failed")
  }

  def "test suite setup failure generates spans"() {
    setup:
    runTestClasses(TestFailedSuiteSetup)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 1, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      trace(2, true) {
        long testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_FAIL)
        testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestFailedSuiteSetup", TestEventsHandler.TEST_FAIL, null, exception)
      }
    })

    where:
    exception = new RuntimeException("suite set up failed")
  }

  def "test categories are included in spans"() {
    setup:
    runTestClasses(TestSucceedWithCategories)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestSucceedWithCategories",
          TestEventsHandler.TEST_PASS, null, null, false,
          ["Slow", "Flaky"])
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedWithCategories", "test_succeed",
          TestEventsHandler.TEST_PASS, null, null, false,
          ["End2end", "Browser", "Slow", "Flaky"])
      }
    })
  }

  def "test assumption failure during suite setup"() {
    setup:
    runTestClasses(TestSuiteSetUpAssumption)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestSuiteSetUpAssumption", TestEventsHandler.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSuiteSetUpAssumption", "test_succeed", TestEventsHandler.TEST_SKIP, testTags, null, true)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Assumption failed: assumption is not true"]
  }

  def "test assumption failure in a multi-test-case suite"() {
    setup:
    runTestClasses(TestAssumptionAndSucceed)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestAssumptionAndSucceed", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestAssumptionAndSucceed", "test_fail_assumption", TestEventsHandler.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestAssumptionAndSucceed", "test_succeed", TestEventsHandler.TEST_PASS)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Assumption failed: assumption is not true"]
  }

  def "test multiple successful suites"() {
    setup:
    runTestClasses(TestSucceed, TestSucceedAndSkipped)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 4, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long firstSuiteId
      long secondSuiteId
      trace(3, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_PASS)
        firstSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestSucceed", TestEventsHandler.TEST_PASS)
        secondSuiteId = testSuiteSpan(it, 2, testModuleId, testModuleId, "org.example.TestSucceedAndSkipped", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, firstSuiteId, "org.example.TestSucceed", "test_succeed", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, secondSuiteId, "org.example.TestSucceedAndSkipped", "test_skipped", TestEventsHandler.TEST_SKIP, testTags, null, true)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, secondSuiteId, "org.example.TestSucceedAndSkipped", "test_succeed", TestEventsHandler.TEST_PASS)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in test"]
  }

  def "test successful suite and failing suite"() {
    setup:
    runTestClasses(TestSucceed, TestFailedAndSucceed)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 5, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long firstSuiteId
      long secondSuiteId
      trace(3, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_FAIL)
        firstSuiteId = testSuiteSpan(it, 2, testModuleId, testModuleId, "org.example.TestSucceed", TestEventsHandler.TEST_PASS)
        secondSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestFailedAndSucceed", TestEventsHandler.TEST_FAIL)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, secondSuiteId, "org.example.TestFailedAndSucceed", "test_another_succeed", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, secondSuiteId, "org.example.TestFailedAndSucceed", "test_failed", TestEventsHandler.TEST_FAIL, null, exception)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, secondSuiteId, "org.example.TestFailedAndSucceed", "test_succeed", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, firstSuiteId, "org.example.TestSucceed", "test_succeed", TestEventsHandler.TEST_PASS)
      }
    })

    where:
    exception = new AssertionFailedError("expected: <true> but was: <false>")
  }

  def "test nested successful suites"() {
    setup:
    runTestClasses(TestSucceedNested)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long topLevelSuiteId
      long nestedSuiteId
      trace(3, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_PASS)
        topLevelSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestSucceedNested", TestEventsHandler.TEST_PASS)
        nestedSuiteId = testSuiteSpan(it, 2, topLevelSuiteId, testModuleId, 'org.example.TestSucceedNested$NestedSuite', TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, nestedSuiteId, 'org.example.TestSucceedNested$NestedSuite', "test_succeed_nested", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, topLevelSuiteId, "org.example.TestSucceedNested", "test_succeed", TestEventsHandler.TEST_PASS)
      }
    })
  }

  def "test nested skipped suites"() {
    setup:
    runTestClasses(TestSkippedNested)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long topLevelSuiteId
      long nestedSuiteId
      trace(3, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_SKIP)
        topLevelSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestSkippedNested", TestEventsHandler.TEST_SKIP, testTags)
        nestedSuiteId = testSuiteSpan(it, 2, topLevelSuiteId, testModuleId, 'org.example.TestSkippedNested$NestedSuite', TestEventsHandler.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, nestedSuiteId, 'org.example.TestSkippedNested$NestedSuite', "test_succeed_nested", TestEventsHandler.TEST_SKIP, testTags, null, true)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, topLevelSuiteId, "org.example.TestSkippedNested", "test_succeed", TestEventsHandler.TEST_SKIP, testTags, null, true)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in class"]
  }

  private static void runTestClasses(Class<?>... classes) {
    DiscoverySelector[] selectors = new DiscoverySelector[classes.length]
    for (i in 0..<classes.length) {
      selectors[i] = selectClass(classes[i])
    }

    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectors)
      .build()

    def launcher = LauncherFactory.create()
    launcher.execute(launcherReq)
  }

  private static void runTestClassesSuppressingExceptions(Class<?>... classes) {
    try {
      runTestClasses(classes)
    } catch (Throwable ignored) {
    }
  }

  @Override
  String expectedOperationPrefix() {
    return "junit"
  }

  @Override
  String expectedTestFramework() {
    return JUnit5Decorator.DECORATE.testFramework()
  }

  @Override
  String expectedTestFrameworkVersion() {
    return "5.8.2"
  }

  @Override
  String component() {
    return JUnit5Decorator.DECORATE.component()
  }
}
