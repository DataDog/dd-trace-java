import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.DDTags
import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.CIConstants
import datadog.trace.api.civisibility.config.SkippableTest
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.CiVisibilityTest
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder
import junit.runner.Version
import org.example.TestAssumption
import org.example.TestAssumptionAndSucceed
import org.example.TestError
import org.example.TestFailed
import org.example.TestFailedAndSucceed
import org.example.TestFailedSuiteSetup
import org.example.TestFailedSuiteTearDown
import org.example.TestInheritance
import org.example.TestParameterized
import org.example.TestSkipped
import org.example.TestSkippedClass
import org.example.TestSucceed
import org.example.TestSucceedAndSkipped
import org.example.TestSucceedWithCategories
import org.example.TestSucceedSuite
import org.example.TestFailedSuiteSetUpAssumption
import org.junit.runner.JUnitCore

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit4Test extends CiVisibilityTest {

  def runner = new JUnitCore()

  def "test success generates spans"() {
    setup:
    runTests(TestSucceed)

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
    runTests(TestInheritance)

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

  def "test failed generates spans"() {
    setup:
    try {
      runTests(TestFailed)
    } catch (Throwable ignored) {
      // Ignored
    }

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
    exception = new AssertionError()
  }

  def "test error generates spans"() {
    setup:
    try {
      runTests(TestError)
    } catch (Throwable ignored) {
      // Ignored
    }

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
    runTests(TestSkipped)

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
    runTests(TestSkippedClass)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_SKIP)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSkippedClass", CIConstants.TEST_SKIP, testTags, null)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_class_another_skipped", "test_class_another_skipped()V", CIConstants.TEST_SKIP, testTags, null)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_class_skipped", "test_class_skipped()V", CIConstants.TEST_SKIP, testTags, null)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in class"]
  }

  def "test success and skipped generates spans"() {
    setup:
    runTests(TestSucceedAndSkipped)

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
    runTests(TestFailedAndSucceed)

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
    exception = new AssertionError()
  }

  def "test suite teardown failure generates spans"() {
    setup:
    runTests(TestFailedSuiteTearDown)

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
    runTests(TestFailedSuiteSetup)

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

  def "test with failing assumptions generated spans"() {
    setup:
    runTests(TestAssumption)

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
    testTags = [(Tags.TEST_SKIP_REASON): "got: <false>, expected: is <true>"]
  }

  def "test categories are included in spans"() {
    setup:
    runTests(TestSucceedWithCategories)

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
          ["org.example.Slow", "org.example.Flaky"])
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestSucceedWithCategories", "test_succeed", "test_succeed()V",
          CIConstants.TEST_PASS, null, null, false,
          ["org.example.Slow", "org.example.Flaky", "org.example.End2End", "org.example.Browser"])
      }
    })
  }

  def "test assumption failure during suite setup"() {
    setup:
    runTests(TestFailedSuiteSetUpAssumption)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long testSuiteId
      trace(3, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_SKIP)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestFailedSuiteSetUpAssumption", CIConstants.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestFailedSuiteSetUpAssumption", "test_succeed", "test_succeed()V", CIConstants.TEST_SKIP, testTags, null)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "got: <false>, expected: is <true>"]
  }

  def "test assumption failure in a multi-test-case suite"() {
    setup:
    runTests(TestAssumptionAndSucceed)

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
    testTags = [(Tags.TEST_SKIP_REASON): "got: <false>, expected: is <true>"]
  }

  def "test multiple successful suites"() {
    setup:
    runTests(TestSucceed, TestSucceedAndSkipped)

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
    runTests(TestSucceed, TestFailedAndSucceed)

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
    exception = new AssertionError()
  }

  def "test parameterized"() {
    setup:
    runTests(TestParameterized)

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
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", "parameterized_test_succeed()V", CIConstants.TEST_PASS, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", "parameterized_test_succeed()V", CIConstants.TEST_PASS, testTags_1)
      }
    })

    where:
    testTags_0 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"parameterized_test_succeed[0]"}}']
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"parameterized_test_succeed[1]"}}']
  }

  def "test ITR skipping"() {
    setup:
    givenSkippableTests([
      new SkippableTest("org.example.TestFailedAndSucceed", "test_another_succeed", null, null),
      new SkippableTest("org.example.TestFailedAndSucceed", "test_failed", null, null),
    ])
    runTests(TestFailedAndSucceed)

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
    testTags = [(Tags.TEST_SKIP_REASON): "Skipped by Datadog Intelligent Test Runner", (Tags.TEST_SKIPPED_BY_ITR): true ]
  }

  def "test ITR skipping for parameterized"() {
    setup:
    givenSkippableTests([
      new SkippableTest("org.example.TestParameterized", "parameterized_test_succeed", testTags_0[Tags.TEST_PARAMETERS], null),
    ])
    runTests(TestParameterized)

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
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", "parameterized_test_succeed()V", CIConstants.TEST_SKIP, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", "parameterized_test_succeed()V", CIConstants.TEST_PASS, testTags_1)
      }
    })

    where:
    testTags_0 = [
      (Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"parameterized_test_succeed[0]"}}',
      (Tags.TEST_SKIP_REASON): "Skipped by Datadog Intelligent Test Runner",
      (Tags.TEST_SKIPPED_BY_ITR): true
    ]
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"parameterized_test_succeed[1]"}}']
  }

  def "test suite runner"() {
    setup:
    runTests(TestSucceedSuite)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testSessionId
      long testModuleId
      long firstSuiteId
      long secondSuiteId
      trace(4, true) {
        testSessionId = testSessionSpan(it, 1, CIConstants.TEST_PASS)
        testModuleId = testModuleSpan(it, 0, testSessionId, CIConstants.TEST_PASS)
        firstSuiteId = testSuiteSpan(it, 2, testSessionId, testModuleId, "org.example.TestSucceedSuite\$FirstTest", CIConstants.TEST_PASS)
        secondSuiteId = testSuiteSpan(it, 3, testSessionId, testModuleId, "org.example.TestSucceedSuite\$SecondTest", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, firstSuiteId, "org.example.TestSucceedSuite\$FirstTest", "testAddition", "testAddition()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testSessionId, testModuleId, secondSuiteId, "org.example.TestSucceedSuite\$SecondTest", "testSubtraction", "testSubtraction()V", CIConstants.TEST_PASS)
      }
    })
  }

  private void runTests(Class<?>... tests) {
    TestEventsHandlerHolder.start()
    runner.run(tests)
    TestEventsHandlerHolder.stop()
  }

  @Override
  String expectedOperationPrefix() {
    return "junit"
  }

  @Override
  String expectedTestFramework() {
    return "junit4"
  }

  @Override
  String expectedTestFrameworkVersion() {
    return Version.id()
  }

  @Override
  String component() {
    return "junit"
  }
}
