import datadog.trace.agent.test.base.TestFrameworkTest
import datadog.trace.api.DisableTestTrace
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.civisibility.TestEventsHandler
import datadog.trace.instrumentation.junit4.JUnit4Decorator
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
class JUnit4Test extends TestFrameworkTest {

  def runner = new JUnitCore()

  def "test success generates spans"() {
    setup:
    runner.run(TestSucceed)

    expect:
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, TestEventsHandler.TEST_PASS)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestSucceed", TestEventsHandler.TEST_PASS)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceed", "test_succeed", TestEventsHandler.TEST_PASS)
      }
    }
  }

  def "test inheritance generates spans"() {
    setup:
    runner.run(TestInheritance)

    expect:
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, TestEventsHandler.TEST_PASS)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestInheritance", TestEventsHandler.TEST_PASS)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestInheritance", "test_succeed", TestEventsHandler.TEST_PASS)
      }
    }
  }

  def "test failed generates spans"() {
    setup:
    try {
      runner.run(TestFailed)
    } catch (Throwable ignored) {
      // Ignored
    }

    expect:
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, TestEventsHandler.TEST_FAIL)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestFailed", TestEventsHandler.TEST_FAIL)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailed", "test_failed", TestEventsHandler.TEST_FAIL, null, exception)
      }
    }

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
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, TestEventsHandler.TEST_FAIL)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestError", TestEventsHandler.TEST_FAIL)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestError", "test_error", TestEventsHandler.TEST_FAIL, null, exception)
      }
    }

    where:
    exception = new IllegalArgumentException("This exception is an example")
  }

  def "test skipped generates spans"() {
    setup:
    runner.run(TestSkipped)

    expect:
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, TestEventsHandler.TEST_SKIP)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestSkipped", TestEventsHandler.TEST_SKIP)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkipped", "test_skipped", TestEventsHandler.TEST_SKIP, testTags, null, true)
      }
    }

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in test"]
  }

  def "test class skipped generated spans"() {
    setup:
    runner.run(TestSkippedClass)

    expect:
    assertTraces(1) {
      trace(4, true) {
        long testModuleId = testModuleSpan(it, 2, TestEventsHandler.TEST_SKIP)
        long testSuiteId = testSuiteSpan(it, 3, testModuleId, "org.example.TestSkippedClass", TestEventsHandler.TEST_SKIP, testTags, null)
        testSpan(it, 1, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_class_skipped", TestEventsHandler.TEST_SKIP, testTags, null, true)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_class_another_skipped", TestEventsHandler.TEST_SKIP, testTags, null, true)
      }
    }

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in class"]
  }

  def "test success and skipped generates spans"() {
    setup:
    runner.run(TestSucceedAndSkipped)

    expect:
    assertTraces(1) {
      trace(4, true) {
        long testModuleId = testModuleSpan(it, 2, TestEventsHandler.TEST_PASS)
        long testSuiteId = testSuiteSpan(it, 3, testModuleId, "org.example.TestSucceedAndSkipped", TestEventsHandler.TEST_PASS)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedAndSkipped", "test_skipped", TestEventsHandler.TEST_SKIP, testTags, null, true)
        testSpan(it, 1, testModuleId, testSuiteId, "org.example.TestSucceedAndSkipped", "test_succeed", TestEventsHandler.TEST_PASS)
      }
    }

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in test"]
  }

  def "test success and failure generates spans"() {
    setup:
    runner.run(TestFailedAndSucceed)

    expect:
    assertTraces(1) {
      trace(5, true) {
        long testModuleId = testModuleSpan(it, 3, TestEventsHandler.TEST_FAIL)
        long testSuiteId = testSuiteSpan(it, 4, testModuleId, "org.example.TestFailedAndSucceed", TestEventsHandler.TEST_FAIL)
        testSpan(it, 2, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_succeed", TestEventsHandler.TEST_PASS)
        testSpan(it, 1, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_failed", TestEventsHandler.TEST_FAIL, null, exception)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedAndSucceed", "test_another_succeed", TestEventsHandler.TEST_PASS)
      }
    }

    where:
    exception = new AssertionError()
  }

  def "test suite teardown failure generates spans"() {
    setup:
    runner.run(TestFailedSuiteTearDown)

    expect:
    assertTraces(1) {
      trace(4, true) {
        long testModuleId = testModuleSpan(it, 2, TestEventsHandler.TEST_FAIL)
        long testSuiteId = testSuiteSpan(it, 3, testModuleId, "org.example.TestFailedSuiteTearDown", TestEventsHandler.TEST_FAIL, null, exception)
        testSpan(it, 1, testModuleId, testSuiteId, "org.example.TestFailedSuiteTearDown", "test_succeed", TestEventsHandler.TEST_PASS)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedSuiteTearDown", "test_another_succeed", TestEventsHandler.TEST_PASS)
      }
    }

    where:
    exception = new RuntimeException("suite tear down failed")
  }

  def "test suite setup failure generates spans"() {
    setup:
    runner.run(TestFailedSuiteSetup)

    expect:
    assertTraces(1) {
      trace(2, true) {
        long testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_FAIL)
        testSuiteSpan(it, 1, testModuleId, "org.example.TestFailedSuiteSetup", TestEventsHandler.TEST_FAIL, null, exception)
      }
    }

    where:
    exception = new RuntimeException("suite set up failed")
  }

  def "test with failing assumptions generated spans"() {
    setup:
    runner.run(TestAssumption)

    expect:
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, TestEventsHandler.TEST_SKIP)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestAssumption", TestEventsHandler.TEST_SKIP)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestAssumption", "test_fail_assumption", TestEventsHandler.TEST_SKIP, testTags)
      }
    }

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "got: <false>, expected: is <true>"]
  }

  def "test categories are included in spans"() {
    setup:
    runner.run(TestSucceedWithCategories)

    expect:
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, TestEventsHandler.TEST_PASS)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestSucceedWithCategories",
          TestEventsHandler.TEST_PASS, null, null, false,
          ["org.example.Slow", "org.example.Flaky"])
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedWithCategories", "test_succeed",
          TestEventsHandler.TEST_PASS, null, null, false,
          ["org.example.Slow", "org.example.Flaky", "org.example.End2End", "org.example.Browser"])
      }
    }
  }

  def "test assumption failure during suite setup"() {
    setup:
    runner.run(TestSuiteSetUpAssumption)

    expect:
    assertTraces(1) {
      trace(3, true) {
        long testModuleId = testModuleSpan(it, 1, TestEventsHandler.TEST_SKIP)
        long testSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestSuiteSetUpAssumption", TestEventsHandler.TEST_SKIP)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSuiteSetUpAssumption", "test_succeed", TestEventsHandler.TEST_SKIP, null, null, true)
      }
    }
  }

  def "test assumption failure in a multi-test-case suite"() {
    setup:
    runner.run(TestAssumptionAndSucceed)

    expect:
    assertTraces(1) {
      trace(4, true) {
        long testModuleId = testModuleSpan(it, 2, TestEventsHandler.TEST_PASS)
        long testSuiteId = testSuiteSpan(it, 3, testModuleId, "org.example.TestAssumptionAndSucceed", TestEventsHandler.TEST_PASS)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestAssumptionAndSucceed", "test_fail_assumption", TestEventsHandler.TEST_SKIP, testTags)
        testSpan(it, 1, testModuleId, testSuiteId, "org.example.TestAssumptionAndSucceed", "test_succeed", TestEventsHandler.TEST_PASS)
      }
    }

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "got: <false>, expected: is <true>"]
  }

  def "test multiple successful suites"() {
    setup:
    runner.run(TestSucceed, TestSucceedAndSkipped)

    expect:
    assertTraces(1) {
      trace(6, true) {
        long testModuleId = testModuleSpan(it, 3, TestEventsHandler.TEST_PASS)

        long firstSuiteId = testSuiteSpan(it, 4, testModuleId, "org.example.TestSucceed", TestEventsHandler.TEST_PASS)
        testSpan(it, 0, testModuleId, firstSuiteId, "org.example.TestSucceed", "test_succeed", TestEventsHandler.TEST_PASS)

        long secondSuiteId = testSuiteSpan(it, 5, testModuleId, "org.example.TestSucceedAndSkipped", TestEventsHandler.TEST_PASS)
        testSpan(it, 1, testModuleId, secondSuiteId, "org.example.TestSucceedAndSkipped", "test_skipped", TestEventsHandler.TEST_SKIP, testTags, null, true)
        testSpan(it, 2, testModuleId, secondSuiteId, "org.example.TestSucceedAndSkipped", "test_succeed", TestEventsHandler.TEST_PASS)
      }
    }

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in test"]
  }

  def "test successful suite and failing suite"() {
    setup:
    runner.run(TestSucceed, TestFailedAndSucceed)

    expect:
    assertTraces(1) {
      trace(7, true) {
        long testModuleId = testModuleSpan(it, 4, TestEventsHandler.TEST_FAIL)

        long firstSuiteId = testSuiteSpan(it, 6, testModuleId, "org.example.TestSucceed", TestEventsHandler.TEST_PASS)
        testSpan(it, 3, testModuleId, firstSuiteId, "org.example.TestSucceed", "test_succeed", TestEventsHandler.TEST_PASS)

        long secondSuiteId = testSuiteSpan(it, 5, testModuleId, "org.example.TestFailedAndSucceed", TestEventsHandler.TEST_FAIL)
        testSpan(it, 2, testModuleId, secondSuiteId, "org.example.TestFailedAndSucceed", "test_succeed", TestEventsHandler.TEST_PASS)
        testSpan(it, 1, testModuleId, secondSuiteId, "org.example.TestFailedAndSucceed", "test_failed", TestEventsHandler.TEST_FAIL, null, exception)
        testSpan(it, 0, testModuleId, secondSuiteId, "org.example.TestFailedAndSucceed", "test_another_succeed", TestEventsHandler.TEST_PASS)
      }
    }

    where:
    exception = new AssertionError()
  }

  def "test parameterized"() {
    setup:
    runner.run(TestParameterized)

    assertTraces(1) {
      trace(4, true) {
        long testModuleId = testModuleSpan(it, 2, TestEventsHandler.TEST_PASS)
        long testSuiteId = testSuiteSpan(it, 3, testModuleId, "org.example.TestParameterized", TestEventsHandler.TEST_PASS)
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", TestEventsHandler.TEST_PASS, testTags_1)
        testSpan(it, 1, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", TestEventsHandler.TEST_PASS, testTags_0)
      }
    }

    where:
    testTags_0 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"parameterized_test_succeed[0]"}}']
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"metadata":{"test_name":"parameterized_test_succeed[1]"}}']
  }

  @Override
  String expectedOperationPrefix() {
    return "junit"
  }

  @Override
  String expectedTestFramework() {
    return JUnit4Decorator.DECORATE.testFramework()
  }

  @Override
  String expectedTestFrameworkVersion() {
    return Version.id()
  }

  @Override
  String component() {
    return JUnit4Decorator.DECORATE.component()
  }
}
