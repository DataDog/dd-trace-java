package datadog.trace.instrumentation.testng

import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.diff.FileDiff
import datadog.trace.civisibility.diff.LineDiff
import org.apache.maven.artifact.versioning.ComparableVersion
import org.example.*
import org.junit.jupiter.api.Assumptions
import org.testng.TestNG
import org.testng.xml.SuiteXmlParser
import org.testng.xml.XmlSuite

abstract class TestNGTest extends CiVisibilityInstrumentationTest {

  static testOutputDir = "build/tmp/test-output"

  static ComparableVersion currentTestNGVersion = new ComparableVersion(TracingListener.FRAMEWORK_VERSION)
  static ComparableVersion testNGv75 = new ComparableVersion("7.5")

  def "test #testcaseName"() {
    runTests(tests, null, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                                          | success | tests
    "test-succeed"                                        | true    | [TestSucceed]
    "test-inheritance"                                    | true    | [TestInheritance]
    "test-failed-${version()}"                            | false   | [TestFailed]
    "test-failed-with-success-percentage-${version()}"    | true    | [TestFailedWithSuccessPercentage]
    "test-error"                                          | false   | [TestError]
    "test-skipped"                                        | true    | [TestSkipped]
    "test-parameterized"                                  | true    | [TestParameterized]
    "test-parameterized-modifies-params"                  | true    | [TestParameterizedModifiesParams]
    "test-success-with-groups"                            | true    | [TestSucceedGroups]
    "test-class-skipped"                                  | true    | [TestSkippedClass]
    "test-success-and-skipped"                            | true    | [TestSucceedAndSkipped]
    "test-success-and-failure-${version()}"               | false   | [TestFailedAndSucceed]
    "test-suite-teardown-failure"                         | false   | [TestFailedSuiteTearDown]
    "test-suite-setup-failure"                            | false   | [TestFailedSuiteSetup]
    "test-multiple-successful-suites"                     | true    | [TestSucceed, TestSucceedAndSkipped]
    "test-successful-suite-and-failed-suite-${version()}" | false   | [TestSucceed, TestFailedAndSucceed]
    "test-nested-successful-suites"                       | true    | [TestSucceedNested, TestSucceedNested.NestedSuite]
    "test-nested-skipped-suites-${version()}"             | true    | [TestSkippedNested]
    "test-factory-data-provider"                          | true    | [TestSucceedDataProvider]
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
    Assumptions.assumeTrue(isExceptionSuppressionSupported())

    givenFlakyRetryEnabled(true)
    givenFlakyTests(retriedTests)
    runTests(tests, null, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                            | success | tests                     | retriedTests
    "test-failed-${version()}"              | false   | [TestFailed]              | []
    "test-skipped"                          | true    | [TestSkipped]             | [new TestIdentifier("org.example.TestSkipped", "test_skipped", null)]
    "test-retry-failed-${version()}"        | false   | [TestFailed]              | [new TestIdentifier("org.example.TestFailed", "test_failed", null)]
    "test-retry-error"                      | false   | [TestError]               | [new TestIdentifier("org.example.TestError", "test_error", null)]
    "test-retry-parameterized"              | false   | [TestFailedParameterized] | [new TestIdentifier("org.example.TestFailedParameterized", "parameterized_test_succeed", null)]
    "test-failed-then-succeed-${version()}" | true    | [TestFailedThenSucceed]   | [new TestIdentifier("org.example.TestFailedThenSucceed", "test_failed", null)]
  }

  def "test early flakiness detection #testcaseName"() {
    Assumptions.assumeTrue(isEFDSupported())

    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runTests(tests, null, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                        | success | tests                  | knownTestsList
    "test-efd-known-test"               | true    | [TestSucceed]          | [new TestIdentifier("org.example.TestSucceed", "test_succeed", null)]
    "test-efd-known-parameterized-test" | true    | [TestParameterized]    | [new TestIdentifier("org.example.TestParameterized", "parameterized_test_succeed", null)]
    "test-efd-new-test"                 | true    | [TestSucceed]          | []
    "test-efd-new-parameterized-test"   | true    | [TestParameterized]    | []
    "test-efd-known-tests-and-new-test" | false   | [TestFailedAndSucceed] | [
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_failed", null),
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_succeed", null)
    ]
    "test-efd-new-slow-test"            | true    | [TestSucceedSlow]      | [] // is executed only twice
    "test-efd-new-very-slow-test"       | true    | [TestSucceedVerySlow]  | [] // is executed only once
    "test-efd-faulty-session-threshold" | false   | [TestFailedAndSucceed] | []
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
    currentTestNGVersion >= testNGv75
  }

  private static boolean isExceptionSuppressionSupported() {
    currentTestNGVersion >= testNGv75
  }

  protected void runTests(List<Class> testClasses, String parallelMode = null, boolean expectSuccess = true) {
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
      if (expectSuccess && testNG.hasFailure()) {
        throw new AssertionError("Expected successful execution, but reported status is failed")
      }
      if (!expectSuccess && !testNG.hasFailure()) {
        throw new AssertionError("Expected failed execution, but reported status is successful")
      }
    } catch (Throwable t) {
      if (expectSuccess) {
        throw new AssertionError("Expected successful execution, got error", t)
      }
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
