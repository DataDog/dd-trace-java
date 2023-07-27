import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.DDTags
import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.CIConstants
import datadog.trace.api.civisibility.config.SkippableTest
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.CiVisibilityTest
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
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
import org.junit.jupiter.engine.JupiterTestEngine
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory
import org.opentest4j.AssertionFailedError

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit5Test extends CiVisibilityTest {

  def "test success generate spans"() {
    setup:
    runTestClasses(TestSucceed)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSucceed", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
    })
  }

  def "test inheritance generates spans"() {
    setup:
    runTestClasses(TestInheritance)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestInheritance", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestInheritance", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
    })
  }

  def "test parameterized generates spans"() {
    setup:
    runTestClasses(TestParameterized)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestParameterized", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestParameterized", "test_parameterized", "test_parameterized(IIILjava/lang/String;)V", CIConstants.TEST_PASS, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestParameterized", "test_parameterized", "test_parameterized(IIILjava/lang/String;)V", CIConstants.TEST_PASS, testTags_1)
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
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestRepeated", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestRepeated", "test_repeated", "test_repeated()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestRepeated", "test_repeated", "test_repeated()V", CIConstants.TEST_PASS)
      }
    })
  }

  def "test template generates spans"() {
    setup:
    runTestClasses(TestTemplate)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestTemplate", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestTemplate", "test_template", "test_template(Lorg/example/TestTemplate\$SampleTestCase;)V", CIConstants.TEST_PASS, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestTemplate", "test_template", "test_template(Lorg/example/TestTemplate\$SampleTestCase;)V", CIConstants.TEST_PASS, testTags_1)
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
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestFactory", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestFactory", "test_factory", "test_factory()Ljava/lang/Iterable;", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestFactory", "test_factory", "test_factory()Ljava/lang/Iterable;", CIConstants.TEST_PASS)
      }
    })
  }

  def "test failed generates spans"() {
    setup:
    runTestClassesSuppressingExceptions(TestFailed)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_FAIL)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestFailed", CIConstants.TEST_FAIL)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestFailed", "test_failed", "test_failed()V", CIConstants.TEST_FAIL, null, exception)
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
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_FAIL)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestError", CIConstants.TEST_FAIL)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestError", "test_error", "test_error()V", CIConstants.TEST_FAIL, null, exception)
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
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_SKIP)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSkipped", CIConstants.TEST_SKIP)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSkipped", "test_skipped", "test_skipped()V", CIConstants.TEST_SKIP, testTags, null)
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
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_SKIP)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSkippedClass", CIConstants.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_case_skipped", "test_case_skipped()V", CIConstants.TEST_SKIP, testTags, null)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_factory_skipped", "test_factory_skipped()Ljava/lang/Iterable;", CIConstants.TEST_SKIP, testTags, null)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_parameterized_skipped", "test_parameterized_skipped(IIILjava/lang/String;)V", CIConstants.TEST_SKIP, parameterizedTestTags, null)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_repeated_skipped", "test_repeated_skipped()V", CIConstants.TEST_SKIP, testTags, null)
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
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_SKIP)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestAssumption", CIConstants.TEST_SKIP)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestAssumption", "test_fail_assumption", "test_fail_assumption()V", CIConstants.TEST_SKIP, testTags)
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
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_SKIP)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestAssumptionLegacy", CIConstants.TEST_SKIP)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestAssumptionLegacy", "test_fail_assumption_legacy", "test_fail_assumption_legacy()V", CIConstants.TEST_SKIP, testTags)
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
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSucceedAndSkipped", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSucceedAndSkipped", "test_skipped", "test_skipped()V", CIConstants.TEST_SKIP, testTags, null)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSucceedAndSkipped", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
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
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_FAIL)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestFailedAndSucceed", CIConstants.TEST_FAIL)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_another_succeed", "test_another_succeed()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_failed", "test_failed()V", CIConstants.TEST_FAIL, null, exception)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
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
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_FAIL)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestFailedSuiteTearDown", CIConstants.TEST_FAIL, null, exception)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestFailedSuiteTearDown", "test_another_succeed", "test_another_succeed()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestFailedSuiteTearDown", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
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
      trace(3, true) {
        long testSessionId = testSessionSpan(it, 1, CIConstants.TEST_FAIL)
        long testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_FAIL)
        testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestFailedSuiteSetup", CIConstants.TEST_FAIL, null, exception)
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
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSucceedWithCategories",
          CIConstants.TEST_PASS, null, null, false,
          ["Slow", "Flaky"])
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSucceedWithCategories", "test_succeed", "test_succeed()V",
          CIConstants.TEST_PASS, null, null, false,
          ["End2end", "Browser", "Slow", "Flaky"])
      }
    })
  }

  def "test assumption failure during suite setup"() {
    setup:
    runTestClasses(TestSuiteSetUpAssumption)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_SKIP)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSuiteSetUpAssumption", CIConstants.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSuiteSetUpAssumption", "test_succeed", "test_succeed()V", CIConstants.TEST_SKIP, testTags, null)
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
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestAssumptionAndSucceed", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestAssumptionAndSucceed", "test_fail_assumption", "test_fail_assumption()V", CIConstants.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestAssumptionAndSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
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
      long testSessionId
      long testModuleId
      long firstSuiteId
      long secondSuiteId
      trace(4, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        firstSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSucceed", CIConstants.TEST_PASS)
        secondSuiteId = testSuiteSpan(it, 3, testSessionId, testModuleId, "org.example.TestSucceedAndSkipped", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, firstSuiteId, "org.example.TestSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, secondSuiteId, "org.example.TestSucceedAndSkipped", "test_skipped", "test_skipped()V", CIConstants.TEST_SKIP, testTags, null)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, secondSuiteId, "org.example.TestSucceedAndSkipped", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
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
      long testSessionId
      long testModuleId
      long firstSuiteId
      long secondSuiteId
      trace(4, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_FAIL)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_FAIL)
        firstSuiteId = testSuiteSpan(it, 3, testSessionId, testModuleId, "org.example.TestSucceed", CIConstants.TEST_PASS)
        secondSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestFailedAndSucceed", CIConstants.TEST_FAIL)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, secondSuiteId, "org.example.TestFailedAndSucceed", "test_another_succeed", "test_another_succeed()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, secondSuiteId, "org.example.TestFailedAndSucceed", "test_failed", "test_failed()V", CIConstants.TEST_FAIL, null, exception)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, secondSuiteId, "org.example.TestFailedAndSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, firstSuiteId, "org.example.TestSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
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
      long testSessionId
      long testModuleId
      long topLevelSuiteId
      long nestedSuiteId
      trace(4, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        topLevelSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSucceedNested", CIConstants.TEST_PASS)
        nestedSuiteId = testSuiteSpan(it, 3, testSessionId, testModuleId, 'org.example.TestSucceedNested$NestedSuite', CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, nestedSuiteId, 'org.example.TestSucceedNested$NestedSuite', "test_succeed_nested", "test_succeed_nested()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, topLevelSuiteId, "org.example.TestSucceedNested", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
    })
  }

  def "test nested skipped suites"() {
    setup:
    runTestClasses(TestSkippedNested)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long topLevelSuiteId
      long nestedSuiteId
      trace(4, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_SKIP)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_SKIP)
        topLevelSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSkippedNested", CIConstants.TEST_SKIP, testTags)
        nestedSuiteId = testSuiteSpan(it, 3, testSessionId, testModuleId, 'org.example.TestSkippedNested$NestedSuite', CIConstants.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, nestedSuiteId, 'org.example.TestSkippedNested$NestedSuite', "test_succeed_nested", "test_succeed_nested()V", CIConstants.TEST_SKIP, testTags, null)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, topLevelSuiteId, "org.example.TestSkippedNested", "test_succeed", "test_succeed()V", CIConstants.TEST_SKIP, testTags, null)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in class"]
  }

  def "test ITR skipping"() {
    setup:
    givenSkippableTests([
      new SkippableTest("org.example.TestFailedAndSucceed", "test_another_succeed", null, null),
      new SkippableTest("org.example.TestFailedAndSucceed", "test_failed", null, null),
    ])
    runTestClasses(TestFailedAndSucceed)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 4, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS, [
          (DDTags.CI_ITR_TESTS_SKIPPED): true,
          (Tags.TEST_ITR_TESTS_SKIPPING_ENABLED): true,
          (Tags.TEST_ITR_TESTS_SKIPPING_TYPE): "test",
          (Tags.TEST_ITR_TESTS_SKIPPING_COUNT): 2,
        ])
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestFailedAndSucceed", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_another_succeed", "test_another_succeed()V", CIConstants.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_failed", "test_failed()V", CIConstants.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Skipped by Datadog Intelligent Test Runner", (Tags.TEST_SKIPPED_BY_ITR): true]
  }

  def "test ITR skipping for parameterized tests"() {
    setup:
    givenSkippableTests([
      new SkippableTest("org.example.TestParameterized", "test_parameterized", testTags_0[Tags.TEST_PARAMETERS], null),
    ])
    runTestClasses(TestParameterized)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS, [
          (DDTags.CI_ITR_TESTS_SKIPPED): true,
          (Tags.TEST_ITR_TESTS_SKIPPING_ENABLED): true,
          (Tags.TEST_ITR_TESTS_SKIPPING_TYPE): "test",
          (Tags.TEST_ITR_TESTS_SKIPPING_COUNT): 1,
        ])
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestParameterized", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestParameterized", "test_parameterized", "test_parameterized(IIILjava/lang/String;)V", CIConstants.TEST_SKIP, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestParameterized", "test_parameterized", "test_parameterized(IIILjava/lang/String;)V", CIConstants.TEST_PASS, testTags_1)
      }
    })

    where:
    testTags_0 = [
      (Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"[1] 0, 0, 0, some:\\\"parameter\\\""}}',
      (Tags.TEST_SKIP_REASON): "Skipped by Datadog Intelligent Test Runner",
      (Tags.TEST_SKIPPED_BY_ITR): true
    ]
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"[2] 1, 1, 2, some:\\\"parameter\\\""}}']
  }

  private static void runTestClasses(Class<?>... classes) {
    TestEventsHandlerHolder.start()

    DiscoverySelector[] selectors = new DiscoverySelector[classes.length]
    for (i in 0..<classes.length) {
      selectors[i] = selectClass(classes[i])
    }

    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .selectors(selectors)
      .build()

    def launcherConfig = LauncherConfig
      .builder()
      .enableTestEngineAutoRegistration(false)
      .addTestEngines(new JupiterTestEngine())
      .build()

    def launcher = LauncherFactory.create(launcherConfig)
    launcher.execute(launcherReq)

    TestEventsHandlerHolder.stop()
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
    return "junit5"
  }

  @Override
  String expectedTestFrameworkVersion() {
    return "5.8.2"
  }

  @Override
  String component() {
    return "junit"
  }
}
