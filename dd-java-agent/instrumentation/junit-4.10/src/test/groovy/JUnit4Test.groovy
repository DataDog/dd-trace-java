import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.CIConstants
import datadog.trace.api.civisibility.config.SkippableTest
import datadog.trace.api.civisibility.config.SkippableTestsSerializer
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.civisibility.CiVisibilityTest
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
import org.example.TestSuiteSetUpAssumption
import org.junit.runner.JUnitCore

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit4Test extends CiVisibilityTest {

  def runner = new JUnitCore()

  def "test success generates spans"() {
    setup:
    runner.run(TestSucceed)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSucceed", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
    })
  }

  def "test inheritance generates spans"() {
    setup:
    runner.run(TestInheritance)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestInheritance", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestInheritance", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
    })
  }

  def "test failed generates spans"() {
    setup:
    try {
      runner.run(TestFailed)
    } catch (Throwable ignored) {
      // Ignored
    }

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestFailed", CIConstants.TEST_FAIL)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailed", "test_failed", "test_failed()V", CIConstants.TEST_FAIL, null, exception)
      }
    })

    where:
    exception = new AssertionError()
  }

  def "test error generates spans"() {
    setup:
    try {
      runner.run(TestError)
    } catch (Throwable ignored) {
      // Ignored
    }

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestError", CIConstants.TEST_FAIL)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestError", "test_error", "test_error()V", CIConstants.TEST_FAIL, null, exception)
      }
    })

    where:
    exception = new IllegalArgumentException("This exception is an example")
  }

  def "test skipped generates spans"() {
    setup:
    runner.run(TestSkipped)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSkipped", CIConstants.TEST_SKIP)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkipped", "test_skipped", "test_skipped()V", CIConstants.TEST_SKIP, testTags, null)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in test"]
  }

  def "test class skipped generated spans"() {
    setup:
    runner.run(TestSkippedClass)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSkippedClass", CIConstants.TEST_SKIP, testTags, null)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_class_another_skipped", "test_class_another_skipped()V", CIConstants.TEST_SKIP, testTags, null)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_class_skipped", "test_class_skipped()V", CIConstants.TEST_SKIP, testTags, null)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in class"]
  }

  def "test success and skipped generates spans"() {
    setup:
    runner.run(TestSucceedAndSkipped)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSucceedAndSkipped", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedAndSkipped", "test_skipped", "test_skipped()V", CIConstants.TEST_SKIP, testTags, null)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedAndSkipped", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in test"]
  }

  def "test success and failure generates spans"() {
    setup:
    runner.run(TestFailedAndSucceed)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 4, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestFailedAndSucceed", CIConstants.TEST_FAIL)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_another_succeed", "test_another_succeed()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_failed", "test_failed()V", CIConstants.TEST_FAIL, null, exception)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
    })

    where:
    exception = new AssertionError()
  }

  def "test suite teardown failure generates spans"() {
    setup:
    runner.run(TestFailedSuiteTearDown)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestFailedSuiteTearDown", CIConstants.TEST_FAIL, null, exception)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedSuiteTearDown", "test_another_succeed", "test_another_succeed()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedSuiteTearDown", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
    })

    where:
    exception = new RuntimeException("suite tear down failed")
  }

  def "test suite setup failure generates spans"() {
    setup:
    runner.run(TestFailedSuiteSetup)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 1, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      trace(2, true) {
        long testModuleId = testModuleSpan(it, 0, CIConstants.TEST_FAIL)
        testSuiteSpan(it, 1, testModuleId, "org.example.TestFailedSuiteSetup", CIConstants.TEST_FAIL, null, exception)
      }
    })

    where:
    exception = new RuntimeException("suite set up failed")
  }

  def "test with failing assumptions generated spans"() {
    setup:
    runner.run(TestAssumption)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestAssumption", CIConstants.TEST_SKIP)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestAssumption", "test_fail_assumption", "test_fail_assumption()V", CIConstants.TEST_SKIP, testTags)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "got: <false>, expected: is <true>"]
  }

  def "test categories are included in spans"() {
    setup:
    runner.run(TestSucceedWithCategories)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSucceedWithCategories",
          CIConstants.TEST_PASS, null, null, false,
          ["org.example.Slow", "org.example.Flaky"])
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedWithCategories", "test_succeed", "test_succeed()V",
          CIConstants.TEST_PASS, null, null, false,
          ["org.example.Slow", "org.example.Flaky", "org.example.End2End", "org.example.Browser"])
      }
    })
  }

  def "test assumption failure during suite setup"() {
    setup:
    runner.run(TestSuiteSetUpAssumption)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSuiteSetUpAssumption", CIConstants.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSuiteSetUpAssumption", "test_succeed", "test_succeed()V", CIConstants.TEST_SKIP, testTags, null)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "got: <false>, expected: is <true>"]
  }

  def "test assumption failure in a multi-test-case suite"() {
    setup:
    runner.run(TestAssumptionAndSucceed)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestAssumptionAndSucceed", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestAssumptionAndSucceed", "test_fail_assumption", "test_fail_assumption()V", CIConstants.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestAssumptionAndSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "got: <false>, expected: is <true>"]
  }

  def "test multiple successful suites"() {
    setup:
    runner.run(TestSucceed, TestSucceedAndSkipped)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 4, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long firstSuiteId
      long secondSuiteId
      trace(3, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        firstSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSucceed", CIConstants.TEST_PASS)
        secondSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestSucceedAndSkipped", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, firstSuiteId, "org.example.TestSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, secondSuiteId, "org.example.TestSucceedAndSkipped", "test_skipped", "test_skipped()V", CIConstants.TEST_SKIP, testTags, null)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, secondSuiteId, "org.example.TestSucceedAndSkipped", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in test"]
  }

  def "test successful suite and failing suite"() {
    setup:
    runner.run(TestSucceed, TestFailedAndSucceed)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 5, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long firstSuiteId
      long secondSuiteId
      trace(3, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_FAIL)
        firstSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestSucceed", CIConstants.TEST_PASS)
        secondSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestFailedAndSucceed", CIConstants.TEST_FAIL)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, secondSuiteId, "org.example.TestFailedAndSucceed", "test_another_succeed", "test_another_succeed()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, secondSuiteId, "org.example.TestFailedAndSucceed", "test_failed", "test_failed()V", CIConstants.TEST_FAIL, null, exception)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, secondSuiteId, "org.example.TestFailedAndSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, firstSuiteId, "org.example.TestSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
    })

    where:
    exception = new AssertionError()
  }

  def "test parameterized"() {
    setup:
    runner.run(TestParameterized)

    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestParameterized", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", "parameterized_test_succeed()V", CIConstants.TEST_PASS, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", "parameterized_test_succeed()V", CIConstants.TEST_PASS, testTags_1)
      }
    })

    where:
    testTags_0 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"parameterized_test_succeed[0]"}}']
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"parameterized_test_succeed[1]"}}']
  }

  def "test ITR skipping"() {
    setup:
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_SKIPPABLE_TESTS, SkippableTestsSerializer.serialize([
      new SkippableTest("org.example.TestFailedAndSucceed", "test_another_succeed", null, null),
      new SkippableTest("org.example.TestFailedAndSucceed", "test_failed", null, null),
    ]))
    runner.run(TestFailedAndSucceed)

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 4, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestFailedAndSucceed", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_another_succeed", "test_another_succeed()V", CIConstants.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_failed", "test_failed()V", CIConstants.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Skipped by Datadog Intelligent Test Runner"]
  }

  def "test ITR skipping for parameterized"() {
    setup:
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_SKIPPABLE_TESTS, SkippableTestsSerializer.serialize([
      new SkippableTest("org.example.TestParameterized", "parameterized_test_succeed", testTags_0[Tags.TEST_PARAMETERS], null),
    ]))
    runner.run(TestParameterized)

    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestParameterized", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", "parameterized_test_succeed()V", CIConstants.TEST_SKIP, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", "parameterized_test_succeed()V", CIConstants.TEST_PASS, testTags_1)
      }
    })

    where:
    testTags_0 = [
      (Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"parameterized_test_succeed[0]"}}',
      (Tags.TEST_SKIP_REASON): "Skipped by Datadog Intelligent Test Runner"
    ]
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"parameterized_test_succeed[1]"}}']
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
