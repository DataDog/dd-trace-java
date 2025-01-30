package datadog.trace.instrumentation.testng

import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.diff.FileDiff
import datadog.trace.civisibility.diff.LineDiff
import org.example.TestError
import org.example.TestFailed
import org.example.TestFailedAndSucceed
import org.example.TestFailedParameterized
import org.example.TestFailedSuiteSetup
import org.example.TestFailedSuiteTearDown
import org.example.TestFailedThenSucceed
import org.example.TestFailedWithSuccessPercentage
import org.example.TestInheritance
import org.example.TestParameterized
import org.example.TestParameterizedModifiesParams
import org.example.TestSkipped
import org.example.TestSkippedClass
import org.example.TestSkippedNested
import org.example.TestSucceed
import org.example.TestSucceedAndSkipped
import org.example.TestSucceedDataProvider
import org.example.TestSucceedGroups
import org.example.TestSucceedMultiple
import org.example.TestSucceedNested
import org.example.TestSucceedSlow
import org.example.TestSucceedUnskippable
import org.example.TestSucceedVerySlow
import org.junit.jupiter.api.Assumptions
import org.testng.TestNG
import org.testng.xml.SuiteXmlParser
import org.testng.xml.XmlSuite

abstract class TestNGTest extends CiVisibilityInstrumentationTest {

  static testOutputDir = "build/tmp/test-output"

  def "test #testcaseName"() {
    runTests(tests, null)

    assertSpansData(testcaseName)

    where:
    testcaseName                                          | tests
    "test-succeed"                                        | [TestSucceed]
    "test-inheritance"                                    | [TestInheritance]
    "test-failed-${version()}"                            | [TestFailed]
    "test-failed-with-success-percentage-${version()}"    | [TestFailedWithSuccessPercentage]
    "test-error"                                          | [TestError]
    "test-skipped"                                        | [TestSkipped]
    "test-parameterized"                                  | [TestParameterized]
    "test-parameterized-modifies-params"                  | [TestParameterizedModifiesParams]
    "test-success-with-groups"                            | [TestSucceedGroups]
    "test-class-skipped"                                  | [TestSkippedClass]
    "test-success-and-skipped"                            | [TestSucceedAndSkipped]
    "test-success-and-failure-${version()}"               | [TestFailedAndSucceed]
    "test-suite-teardown-failure"                         | [TestFailedSuiteTearDown]
    "test-suite-setup-failure"                            | [TestFailedSuiteSetup]
    "test-multiple-successful-suites"                     | [TestSucceed, TestSucceedAndSkipped]
    "test-successful-suite-and-failed-suite-${version()}" | [TestSucceed, TestFailedAndSucceed]
    "test-nested-successful-suites"                       | [TestSucceedNested, TestSucceedNested.NestedSuite]
    "test-nested-skipped-suites-${version()}"             | [TestSkippedNested]
    "test-factory-data-provider"                          | [TestSucceedDataProvider]
  }

  def "test parallel execution #testcaseName"() {
    runTests(tests, parallelMode)

    assertSpansData(testcaseName)

    where:
    testcaseName                                | tests                 | parallelMode
    "test-successful-test-cases-in-parallel"    | [TestSucceedMultiple] | "methods"
    "test-parameterized-test-cases-in-parallel" | [TestParameterized]   | "methods"
  }

  def "test XML suites #testcaseName"() {
    def xmlSuite = null
    TestNGTest.classLoader.getResourceAsStream(testcaseName + "/suite.xml").withCloseable {
      xmlSuite = new SuiteXmlParser().parse("testng.xml", it, true)
    }
    runXmlSuites([xmlSuite], parallelMode)

    assertSpansData(testcaseName)

    where:
    testcaseName                                                                      | parallelMode
    "test-successful-test-cases-in-TESTS-parallel-mode"                               | "tests"
    "test-successful-test-cases-in-TESTS-parallel-mode-not-all-test-methods-included" | "tests"
    "test-successful-test-cases-in-TESTS-parallel-mode-same-test-case"                | "tests"
  }

  def "test ITR #testcaseName"() {
    givenSkippableTests(skippedTests)
    runTests(tests, null)

    assertSpansData(testcaseName)

    where:
    testcaseName                              | tests                     | skippedTests
    "test-itr-skipping"                       | [TestFailedAndSucceed]    | [
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_another_succeed", null),
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_failed", null)
    ]
    "test-itr-skipping-parameterized"         | [TestParameterized]       | [
      new TestIdentifier("org.example.TestParameterized", "parameterized_test_succeed", '{"arguments":{"0":"hello","1":"true"}}')
    ]
    "test-itr-skipping-factory-data-provider" | [TestSucceedDataProvider] | [new TestIdentifier("org.example.TestSucceedDataProvider", "testMethod", null)]
    "test-itr-unskippable"                    | [TestSucceedUnskippable]  | [new TestIdentifier("org.example.TestSucceedUnskippable", "test_succeed", null)]
    "test-itr-unskippable-not-skipped"        | [TestSucceedUnskippable]  | []
  }

  def "test flaky retries #testcaseName"() {
    givenFlakyRetryEnabled(true)
    givenFlakyTests(retriedTests)
    runTests(tests, null)

    assertSpansData(testcaseName)

    where:
    testcaseName                            | tests                     | retriedTests
    "test-failed-${version()}"              | [TestFailed]              | []
    "test-skipped"                          | [TestSkipped]             | [new TestIdentifier("org.example.TestSkipped", "test_skipped", null)]
    "test-retry-failed-${version()}"        | [TestFailed]              | [new TestIdentifier("org.example.TestFailed", "test_failed", null)]
    "test-retry-error"                      | [TestError]               | [new TestIdentifier("org.example.TestError", "test_error", null)]
    "test-retry-parameterized"              | [TestFailedParameterized] | [new TestIdentifier("org.example.TestFailedParameterized", "parameterized_test_succeed", null)]
    "test-failed-then-succeed-${version()}" | [TestFailedThenSucceed]   | [new TestIdentifier("org.example.TestFailedThenSucceed", "test_failed", null)]
  }

  def "test early flakiness detection #testcaseName"() {
    Assumptions.assumeTrue(isEFDSupported())

    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                        | tests                  | knownTestsList
    "test-efd-known-test"               | [TestSucceed]          | [new TestIdentifier("org.example.TestSucceed", "test_succeed", null)]
    "test-efd-known-parameterized-test" | [TestParameterized]    | [new TestIdentifier("org.example.TestParameterized", "parameterized_test_succeed", null)]
    "test-efd-new-test"                 | [TestSucceed]          | []
    "test-efd-new-parameterized-test"   | [TestParameterized]    | []
    "test-efd-known-tests-and-new-test" | [TestFailedAndSucceed] | [
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_failed", null),
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_succeed", null)
    ]
    "test-efd-new-slow-test"            | [TestSucceedSlow]      | [] // is executed only twice
    "test-efd-new-very-slow-test"       | [TestSucceedVerySlow]  | [] // is executed only once
    "test-efd-faulty-session-threshold" | [TestFailedAndSucceed] | []
  }

  def "test impacted tests detection #testcaseName"() {
    givenImpactedTestsDetectionEnabled(true)
    givenDiff(prDiff)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName            | tests         | prDiff
    "test-succeed"          | [TestSucceed] | LineDiff.EMPTY
    "test-succeed"          | [TestSucceed] | new FileDiff(new HashSet())
    "test-succeed-impacted" | [TestSucceed] | new FileDiff(new HashSet([DUMMY_SOURCE_PATH]))
    "test-succeed"          | [TestSucceed] | new LineDiff([(DUMMY_SOURCE_PATH): lines()])
    "test-succeed-impacted" | [TestSucceed] | new LineDiff([(DUMMY_SOURCE_PATH): lines(DUMMY_TEST_METHOD_START)])
  }

  private static boolean isEFDSupported() {
    TracingListener.FRAMEWORK_VERSION >= "7.5"
  }

  protected void runTests(List<Class> testClasses, String parallelMode = null) {
    TestEventsHandlerHolder.start()

    def testNG = new TestNG()
    testNG.setOutputDirectory(testOutputDir)
    Class[] testClassesArray = testClasses.toArray(new Class[0])
    testNG.setTestClasses(testClassesArray)
    if (parallelMode != null) {
      testNG.setParallel(parallelMode)
    }

    try {
      testNG.run()
    } catch (Throwable ignored) {
      // Ignored
    }

    TestEventsHandlerHolder.stop()
  }

  private void runXmlSuites(List<XmlSuite> suites, String parallelMode = null) {
    TestEventsHandlerHolder.start()

    def testNG = new TestNG()
    testNG.setOutputDirectory(testOutputDir)
    testNG.setXmlSuites(suites)
    if (parallelMode != null) {
      testNG.setParallel(parallelMode)
    }

    try {
      testNG.run()
    } catch (Throwable ignored) {
      // Ignored
    }

    TestEventsHandlerHolder.stop()
  }

  protected abstract String version()

  @Override
  String instrumentedLibraryName() {
    TracingListener.FRAMEWORK_NAME
  }

  @Override
  String instrumentedLibraryVersion() {
    TracingListener.FRAMEWORK_VERSION
  }
}
