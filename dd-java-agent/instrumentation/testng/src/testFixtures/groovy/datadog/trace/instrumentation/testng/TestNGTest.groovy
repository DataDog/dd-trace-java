package datadog.trace.instrumentation.testng

import datadog.trace.agent.test.asserts.ListWriterAssert
import datadog.trace.api.civisibility.config.SkippableTest
import datadog.trace.api.civisibility.config.SkippableTestsSerializer
import datadog.trace.api.config.CiVisibilityConfig
import datadog.trace.civisibility.CiVisibilityTest
import datadog.trace.api.civisibility.CIConstants
import datadog.trace.bootstrap.instrumentation.api.Tags
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
import org.example.TestSucceedMultiple
import org.example.TestSucceedNested
import org.testng.TestNG
import org.testng.xml.SuiteXmlParser

abstract class TestNGTest extends CiVisibilityTest {

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
    def testNG = new TestNG()
    testNG.setTestClasses(TestInheritance)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

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
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestFailed", CIConstants.TEST_FAIL)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailed", "test_failed", "test_failed()V", CIConstants.TEST_FAIL, null, exception)
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
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestFailedWithSuccessPercentage", CIConstants.TEST_FAIL)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedWithSuccessPercentage", "test_failed_with_success_percentage", "test_failed_with_success_percentage()V", CIConstants.TEST_FAIL, null, exception)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedWithSuccessPercentage", "test_failed_with_success_percentage", "test_failed_with_success_percentage()V", CIConstants.TEST_FAIL, null, exception)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedWithSuccessPercentage", "test_failed_with_success_percentage", "test_failed_with_success_percentage()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedWithSuccessPercentage", "test_failed_with_success_percentage", "test_failed_with_success_percentage()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedWithSuccessPercentage", "test_failed_with_success_percentage", "test_failed_with_success_percentage()V", CIConstants.TEST_PASS)
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
    def testNG = new TestNG()
    testNG.setTestClasses(TestSkipped)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 2, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSkipped", CIConstants.TEST_SKIP)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkipped", "test_skipped", "test_skipped()V", CIConstants.TEST_SKIP, testTags)
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
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestParameterized", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", "parameterized_test_succeed(Ljava/lang/String;Z)V", CIConstants.TEST_PASS, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", "parameterized_test_succeed(Ljava/lang/String;Z)V", CIConstants.TEST_PASS, testTags_1)
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
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSucceedGroups", CIConstants.TEST_PASS,
          null, null, false, ["classGroup", "parentGroup"])
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedGroups", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS,
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
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_SKIP)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSkippedClass", CIConstants.TEST_SKIP)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_class_another_skipped", "test_class_another_skipped()V", CIConstants.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSkippedClass", "test_class_skipped", "test_class_skipped()V", CIConstants.TEST_SKIP, testTags)
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
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSucceedAndSkipped", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedAndSkipped", "test_skipped", "test_skipped()V", CIConstants.TEST_SKIP, testTags)
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
    def testNG = new TestNG()
    testNG.setTestClasses(TestFailedAndSucceed)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

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
    def testNG = new TestNG()
    testNG.setTestClasses(TestFailedSuiteSetup)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_FAIL)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestFailedSuiteSetup", CIConstants.TEST_FAIL, null, exception)
      }
      // if suite set up fails, TestNG will report that suite's test cases as skipped
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedSuiteSetup", "test_another_succeed", "test_another_succeed()V", CIConstants.TEST_SKIP, testTags)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestFailedSuiteSetup", "test_succeed", "test_succeed()V", CIConstants.TEST_SKIP, testTags)
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
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        firstSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSucceed", CIConstants.TEST_PASS)
        secondSuiteId = testSuiteSpan(it, 2, testModuleId, "org.example.TestSucceedAndSkipped", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, firstSuiteId, "org.example.TestSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, secondSuiteId, "org.example.TestSucceedAndSkipped", "test_skipped", "test_skipped()V", CIConstants.TEST_SKIP, testTags)
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
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        topLevelSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSucceedNested", CIConstants.TEST_PASS)
        nestedSuiteId = testSuiteSpan(it, 2, testModuleId, 'org.example.TestSucceedNested$NestedSuite', CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, nestedSuiteId, 'org.example.TestSucceedNested$NestedSuite', "test_succeed_nested", "test_succeed_nested()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, topLevelSuiteId, "org.example.TestSucceedNested", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
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
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        topLevelSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSkippedNested", CIConstants.TEST_SKIP)
        nestedSuiteId = testSuiteSpan(it, 2, testModuleId, 'org.example.TestSkippedNested$NestedSuite', CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, nestedSuiteId, 'org.example.TestSkippedNested$NestedSuite', "test_succeed_nested", "test_succeed_nested()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, topLevelSuiteId, "org.example.TestSkippedNested", "test_succeed", "test_succeed()V", CIConstants.TEST_SKIP, testTags)
      }
    })

    where:
    testTags = testCaseTagsIfSuiteSetUpFailedOrSkipped("Ignore reason in class")
  }

  def "test successful test cases executed in parallel"() {
    setup:
    def testNG = new TestNG()
    testNG.setTestClasses(TestSucceedMultiple)
    testNG.setOutputDirectory(testOutputDir)
    testNG.setParallel("methods")
    testNG.run()

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSucceedMultiple", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedMultiple", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedMultiple", "test_succeed_another", "test_succeed_another()V", CIConstants.TEST_PASS)
      }
    })
  }

  def "test parameterized tests executed in parallel"() {
    setup:
    def testNG = new TestNG()
    testNG.setTestClasses(TestParameterized)
    testNG.setOutputDirectory(testOutputDir)
    testNG.setParallel("methods")
    testNG.run()

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestParameterized", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", "parameterized_test_succeed(Ljava/lang/String;Z)V", CIConstants.TEST_PASS, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", "parameterized_test_succeed(Ljava/lang/String;Z)V", CIConstants.TEST_PASS, testTags_1)
      }
    })

    where:
    testTags_0 = [(Tags.TEST_PARAMETERS): '{"arguments":{"0":"hello","1":"true"}}']
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"arguments":{"0":"\\\"goodbye\\\"","1":"false"}}']
  }

  def "test successful test cases executed in parallel with TESTS parallel mode"() {
    setup:
    def suiteXml = """
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd" >
<suite name="API Test Suite" parallel="tests" configfailurepolicy="continue">
    <test name="Test A">
        <classes>
            <class name="org.example.TestSucceedMultiple">
                <methods>
                    <include name="test_succeed"/>
                </methods>
            </class>
        </classes>
    </test>

    <test name="Test B">
        <classes>
            <class name="org.example.TestSucceedMultiple">
                <methods>
                    <include name="test_succeed_another"/>
                </methods>
            </class>
        </classes>
    </test>
</suite>
    """

    def parser = new SuiteXmlParser()
    def xmlSuite = parser.parse("testng.xml", new ByteArrayInputStream(suiteXml.bytes), true)

    def testNG = new TestNG()
    testNG.setOutputDirectory(testOutputDir)
    testNG.setParallel("tests")
    testNG.setXmlSuites(Collections.singletonList(xmlSuite))
    testNG.run()

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSucceedMultiple", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedMultiple", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedMultiple", "test_succeed_another", "test_succeed_another()V", CIConstants.TEST_PASS)
      }
    })
  }

  def "test successful test cases executed in parallel with TESTS parallel mode and not all test methods included"() {
    setup:
    def suiteXml = """
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd" >
<suite name="API Test Suite" parallel="tests" configfailurepolicy="continue">
    <test name="Test A">
        <classes>
            <class name="org.example.TestSucceedThreeCases">
                <methods>
                    <include name="test_succeed_a"/>
                </methods>
            </class>
        </classes>
    </test>

    <test name="Test B">
        <classes>
            <class name="org.example.TestSucceedThreeCases">
                <methods>
                    <include name="test_succeed_b"/>
                </methods>
            </class>
        </classes>
    </test>
</suite>
    """

    def parser = new SuiteXmlParser()
    def xmlSuite = parser.parse("testng.xml", new ByteArrayInputStream(suiteXml.bytes), true)

    def testNG = new TestNG()
    testNG.setOutputDirectory(testOutputDir)
    testNG.setParallel("tests")
    testNG.setXmlSuites(Collections.singletonList(xmlSuite))
    testNG.run()

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSucceedThreeCases", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedThreeCases", "test_succeed_a", "test_succeed_a()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceedThreeCases", "test_succeed_b", "test_succeed_b()V", CIConstants.TEST_PASS)
      }
    })
  }

  def "test successful test cases executed in parallel with TESTS parallel mode and same test case running concurrently"() {
    setup:
    def suiteXml = """
<!DOCTYPE suite SYSTEM "https://testng.org/testng-1.0.dtd" >
<suite name="API Test Suite" parallel="tests" configfailurepolicy="continue">
    <test name="Test A">
        <classes>
            <class name="org.example.TestSucceed">
                <methods>
                    <include name="test_succeed"/>
                </methods>
            </class>
        </classes>
    </test>

    <test name="Test B">
        <classes>
            <class name="org.example.TestSucceed">
                <methods>
                    <include name="test_succeed"/>
                </methods>
            </class>
        </classes>
    </test>
    
    <test name="Test C">
        <classes>
            <class name="org.example.TestSucceed">
                <methods>
                    <include name="test_succeed"/>
                </methods>
            </class>
        </classes>
    </test>
</suite>
    """

    def parser = new SuiteXmlParser()
    def xmlSuite = parser.parse("testng.xml", new ByteArrayInputStream(suiteXml.bytes), true)

    def testNG = new TestNG()
    testNG.setOutputDirectory(testOutputDir)
    testNG.setParallel("tests")
    testNG.setXmlSuites(Collections.singletonList(xmlSuite))
    testNG.run()

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 4, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestSucceed", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestSucceed", "test_succeed", "test_succeed()V", CIConstants.TEST_PASS)
      }
    })
  }

  def "test ITR skipping"() {
    setup:
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_SKIPPABLE_TESTS, SkippableTestsSerializer.serialize([
      new SkippableTest("org.example.TestFailedAndSucceed", "test_another_succeed", null, null),
      new SkippableTest("org.example.TestFailedAndSucceed", "test_failed", null, null),
    ]))

    def testNG = new TestNG()
    testNG.setTestClasses(TestFailedAndSucceed)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

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

  def "test ITR skipping for parameterized tests"() {
    setup:
    injectSysConfig(CiVisibilityConfig.CIVISIBILITY_SKIPPABLE_TESTS, SkippableTestsSerializer.serialize([
      new SkippableTest("org.example.TestParameterized", "parameterized_test_succeed", testTags_0[Tags.TEST_PARAMETERS], null),
    ]))

    def testNG = new TestNG()
    testNG.setTestClasses(TestParameterized)
    testNG.setOutputDirectory(testOutputDir)
    testNG.run()

    expect:
    ListWriterAssert.assertTraces(TEST_WRITER, 3, false, SORT_TRACES_BY_DESC_SIZE_THEN_BY_NAMES, {
      long testModuleId
      long testSuiteId
      trace(2, true) {
        testModuleId = testModuleSpan(it, 0, CIConstants.TEST_PASS)
        testSuiteId = testSuiteSpan(it, 1, testModuleId, "org.example.TestParameterized", CIConstants.TEST_PASS)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", "parameterized_test_succeed(Ljava/lang/String;Z)V", CIConstants.TEST_SKIP, testTags_0)
      }
      trace(1) {
        testSpan(it, 0, testModuleId, testSuiteId, "org.example.TestParameterized", "parameterized_test_succeed", "parameterized_test_succeed(Ljava/lang/String;Z)V", CIConstants.TEST_PASS, testTags_1)
      }
    })

    where:
    testTags_0 = [
      (Tags.TEST_PARAMETERS): '{"arguments":{"0":"hello","1":"true"}}',
      (Tags.TEST_SKIP_REASON): "Skipped by Datadog Intelligent Test Runner"
    ]
    testTags_1 = [(Tags.TEST_PARAMETERS): '{"arguments":{"0":"\\\"goodbye\\\"","1":"false"}}']
  }

  @Override
  String expectedOperationPrefix() {
    return "testng"
  }

  @Override
  String expectedTestFramework() {
    return "testng"
  }

  @Override
  String component() {
    return "testng"
  }

  abstract String assertionErrorMessage()

  abstract String classSkipReason()

  abstract Map<String, String> testCaseTagsIfSuiteSetUpFailedOrSkipped(String skipReason)
}
