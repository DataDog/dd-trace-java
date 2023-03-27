package datadog.trace.instrumentation.testng

import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.agent.test.base.TestFrameworkTest
import datadog.trace.bootstrap.instrumentation.api.Tags
import datadog.trace.bootstrap.instrumentation.civisibility.TestEventsHandler
import org.example.TestError
import org.example.TestFailed
import org.example.TestFailedAndSucceed
import org.example.TestFailedSuiteSetup
import org.example.TestFailedSuiteTearDown
import org.example.TestFailedWithSuccessPercentage
import org.example.TestInheritance
import org.example.TestParameterized
import org.example.TestSkipped
import org.example.TestSkippedClass
import org.example.TestSkippedNested
import org.example.TestSucceed
import org.example.TestSucceedAndSkipped
import org.example.TestSucceedGroups
import org.example.TestSucceedNested
import org.testng.TestNG

abstract class TestNGTest extends TestFrameworkTest {

  static testOutputDir = "build/tmp/test-output"

  def "test success generates spans"() {
    setup:
    def testNG = new TestNG()
    testNG.setTestClasses(TestSucceed)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

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
    def testNG = new TestNG()
    testNG.setTestClasses(TestInheritance)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

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

  def "test failed generates spans"() {
    setup:
    try {
      def testNG = new TestNG()
      testNG.setTestClasses(TestFailed)
      testNG.setOutputDirectory(testOutputDir)
      testNG.run()
    } catch (Throwable ignored) {
      // Ignored
    }

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
    exception = new AssertionError(assertionErrorMessage(), null)
  }

  def "test failed with success percentage generates spans"() {
    setup:
    try {
      def testNG = new TestNG()
      testNG.setTestClasses(TestFailedWithSuccessPercentage)
      testNG.setOutputDirectory(testOutputDir)
      testNG.run()
    } catch (Throwable ignored) {
      // Ignored
    }

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 6, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestFailedWithSuccessPercentage", TestEventsHandler.TEST_FAIL)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedWithSuccessPercentage", "test_failed_with_success_percentage", TestEventsHandler.TEST_FAIL, null, exception)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedWithSuccessPercentage", "test_failed_with_success_percentage", TestEventsHandler.TEST_FAIL, null, exception)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedWithSuccessPercentage", "test_failed_with_success_percentage", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedWithSuccessPercentage", "test_failed_with_success_percentage", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedWithSuccessPercentage", "test_failed_with_success_percentage", TestEventsHandler.TEST_PASS)
      }
    })

    where:
    exception = new AssertionError(assertionErrorMessage(), null)
  }

  def "test error generates spans"() {
    setup:
    def testNG = new TestNG()
    testNG.setTestClasses(TestError)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

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
    def testNG = new TestNG()
    testNG.setTestClasses(TestSkipped)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestSkipped", TestEventsHandler.TEST_SKIP)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkipped", "test_skipped", TestEventsHandler.TEST_SKIP, testTags)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): "Ignore reason in test"]
  }

  def "test parameterized generates spans"() {
    setup:
    def testNG = new TestNG()
    testNG.setTestClasses(TestParameterized)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestParameterized", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", TestEventsHandler.TEST_PASS, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", TestEventsHandler.TEST_PASS, testTags_1)
      }
    })

    where:
    testTags_0 = [(Tags.TEST_PARAMETERS): '{"arguments":{"0":"hello","1":"true"}}']
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"arguments":{"0":"\\\"goodbye\\\"","1":"false"}}']
  }

  def "test success with groups generates spans"() {
    setup:
    def testNG = new TestNG()
    testNG.setTestClasses(TestSucceedGroups)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestSucceedGroups", TestEventsHandler.TEST_PASS,
          null, null, false, ["classGroup", "parentGroup"])
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedGroups", "test_succeed", TestEventsHandler.TEST_PASS,
          null, null, false, ["classGroup", "testCaseGroup", "parentGroup"])
      }
    })
  }

  def "test class skipped generated spans"() {
    setup:
    def testNG = new TestNG()
    testNG.setTestClasses(TestSkippedClass)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestSkippedClass", TestEventsHandler.TEST_SKIP)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_class_another_skipped", TestEventsHandler.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_class_skipped", TestEventsHandler.TEST_SKIP, testTags)
      }
    })

    where:
    testTags = [(Tags.TEST_SKIP_REASON): classSkipReason()] // framework versions used in tests cannot provide skip reason
  }


  def "test success and skipped generates spans"() {
    setup:
    def testNG = new TestNG()
    testNG.setTestClasses(TestSucceedAndSkipped)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestSucceedAndSkipped", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedAndSkipped", "test_skipped", TestEventsHandler.TEST_SKIP, testTags)
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
    def testNG = new TestNG()
    testNG.setTestClasses(TestFailedAndSucceed)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

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
    exception = new AssertionError(assertionErrorMessage(), null)
  }

  def "test suite teardown failure generates spans"() {
    setup:
    def testNG = new TestNG()
    testNG.setTestClasses(TestFailedSuiteTearDown)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

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
    def testNG = new TestNG()
    testNG.setTestClasses(TestFailedSuiteSetup)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestFailedSuiteSetup", TestEventsHandler.TEST_FAIL, null, exception)
      }
      // if suite set up fails, TestNG will report that suite's test cases as skipped
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedSuiteSetup", "test_another_succeed", TestEventsHandler.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedSuiteSetup", "test_succeed", TestEventsHandler.TEST_SKIP, testTags)
      }
    })

    where:
    exception = new RuntimeException("suite set up failed")
    testTags = testCaseTagsIfSuiteSetUpFailedOrSkipped(exception.message)
  }

  def "test multiple successful suites"() {
    setup:
    def testNG = new TestNG()
    testNG.setTestClasses(TestSucceed, TestSucceedAndSkipped)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

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
        testSpan(it, 0, testModuleId, secondSuiteId, "org.example.TestSucceedAndSkipped", "test_skipped", TestEventsHandler.TEST_SKIP, testTags)
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
    def testNG = new TestNG()
    testNG.setTestClasses(TestSucceed, TestFailedAndSucceed)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

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
    exception = new AssertionError(assertionErrorMessage(), null)
  }

  def "test nested successful suites"() {
    setup:
    def testNG = new TestNG()
    testNG.setTestClasses(TestSucceedNested, TestSucceedNested.NestedSuite)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long topLevelSuiteId
      long nestedSuiteId
      trace(3, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_PASS)
        topLevelSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestSucceedNested", TestEventsHandler.TEST_PASS)
        nestedSuiteId = testSuiteSpan(it, 2, testModuleId, testModuleId, 'org.example.TestSucceedNested$NestedSuite', TestEventsHandler.TEST_PASS)
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
    def testNG = new TestNG()
    testNG.setTestClasses(TestSkippedNested)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long topLevelSuiteId
      long nestedSuiteId
      trace(3, true) {
        testModuleId = testModuleSpan(it, 0, TestEventsHandler.TEST_PASS)
        topLevelSuiteId = testSuiteSpan(it, 1, testModuleId, testModuleId, "org.example.TestSkippedNested", TestEventsHandler.TEST_SKIP)
        nestedSuiteId = testSuiteSpan(it, 2, testModuleId, testModuleId, 'org.example.TestSkippedNested$NestedSuite', TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, nestedSuiteId, 'org.example.TestSkippedNested$NestedSuite', "test_succeed_nested", TestEventsHandler.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, topLevelSuiteId, "org.example.TestSkippedNested", "test_succeed", TestEventsHandler.TEST_SKIP, testTags)
      }
    })

    where:
    testTags = testCaseTagsIfSuiteSetUpFailedOrSkipped("Ignore reason in class")
  }

  @Override
  String expectedOperationPrefix() {
    return "testng"
  }

  @Override
  String expectedTestFramework() {
    return TestNGDecorator.DECORATE.testFramework()
  }

  @Override
  String component() {
    return TestNGDecorator.DECORATE.component()
  }

  abstract String assertionErrorMessage()

  abstract String classSkipReason()

  abstract Map<String, String> testCaseTagsIfSuiteSetUpFailedOrSkipped(String skipReason)
}
