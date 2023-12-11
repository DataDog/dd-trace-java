package datadog.trace.instrumentation.testng


import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
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
import org.example.TestSucceedDataProvider
import org.example.TestSucceedGroups
import org.example.TestSucceedMultiple
import org.example.TestSucceedNested
import org.example.TestSucceedUnskippable
import org.testng.TestNG
import org.testng.xml.SuiteXmlParser
import org.testng.xml.XmlSuite

abstract class TestNGTest extends CiVisibilityInstrumentationTest {

  static testOutputDir = "build/tmp/test-output"

  def "test #testcaseName"() {
    givenSkippableTests(skippedTests)

    if (!tests.isEmpty()) {
      runTests(tests, parallelMode)
    } else {
      def xmlSuite = null
      TestNGTest.classLoader.getResourceAsStream(testcaseName + "/suite.xml").withCloseable {
        xmlSuite = new SuiteXmlParser().parse("testng.xml", it, true)
      }
      runXmlSuites([xmlSuite], parallelMode)
    }

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                                                                      | tests                                              | expectedTracesCount | parallelMode | skippedTests
    "test-succeed"                                                                    | [TestSucceed]                                      | 2                   | null         | []
    "test-inheritance"                                                                | [TestInheritance]                                  | 2                   | null         | []
    "test-failed-${version()}"                                                        | [TestFailed]                                       | 2                   | null         | []
    "test-failed-with-success-percentage-${version()}"                                | [TestFailedWithSuccessPercentage]                  | 6                   | null         | []
    "test-error"                                                                      | [TestError]                                        | 2                   | null         | []
    "test-skipped"                                                                    | [TestSkipped]                                      | 2                   | null         | []
    "test-parameterized"                                                              | [TestParameterized]                                | 3                   | null         | []
    "test-success-with-groups"                                                        | [TestSucceedGroups]                                | 2                   | null         | []
    "test-class-skipped"                                                              | [TestSkippedClass]                                 | 3                   | null         | []
    "test-success-and-skipped"                                                        | [TestSucceedAndSkipped]                            | 3                   | null         | []
    "test-success-and-failure-${version()}"                                           | [TestFailedAndSucceed]                             | 4                   | null         | []
    "test-suite-teardown-failure"                                                     | [TestFailedSuiteTearDown]                          | 3                   | null         | []
    "test-suite-setup-failure"                                                        | [TestFailedSuiteSetup]                             | 3                   | null         | []
    "test-multiple-successful-suites"                                                 | [TestSucceed, TestSucceedAndSkipped]               | 4                   | null         | []
    "test-successful-suite-and-failed-suite-${version()}"                             | [TestSucceed, TestFailedAndSucceed]                | 5                   | null         | []
    "test-nested-successful-suites"                                                   | [TestSucceedNested, TestSucceedNested.NestedSuite] | 3                   | null         | []
    "test-nested-skipped-suites-${version()}"                                         | [TestSkippedNested]                                | 3                   | null         | []
    "test-factory-data-provider"                                                      | [TestSucceedDataProvider]                          | 2                   | null         | []
    "test-successful-test-cases-in-parallel"                                          | [TestSucceedMultiple]                              | 3                   | "methods"    | []
    "test-parameterized-test-cases-in-parallel"                                       | [TestParameterized]                                | 3                   | "methods"    | []
    "test-itr-skipping"                                                               | [TestFailedAndSucceed]                             | 4                   | null         | [
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_another_succeed", null, null),
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_failed", null, null)
    ]
    "test-itr-skipping-parameterized"                                                 | [TestParameterized]                                | 3                   | null         | [
      new TestIdentifier("org.example.TestParameterized", "parameterized_test_succeed", '{"arguments":{"0":"hello","1":"true"}}', null)
    ]
    "test-itr-skipping-factory-data-provider"                                         | [TestSucceedDataProvider]                          | 2                   | null         | [new TestIdentifier("org.example.TestSucceedDataProvider", "testMethod", null, null)]
    "test-itr-unskippable"                                                            | [TestSucceedUnskippable]                           | 2                   | null         | [new TestIdentifier("org.example.TestSucceedUnskippable", "test_succeed", null, null)]
    "test-itr-unskippable-not-skipped"                                                | [TestSucceedUnskippable]                           | 2                   | null         | []
    "test-successful-test-cases-in-TESTS-parallel-mode"                               | []                                                 | 3                   | "tests"      | []
    "test-successful-test-cases-in-TESTS-parallel-mode-not-all-test-methods-included" | []                                                 | 3                   | "tests"      | []
    "test-successful-test-cases-in-TESTS-parallel-mode-same-test-case"                | []                                                 | 4                   | "tests"      | []
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
