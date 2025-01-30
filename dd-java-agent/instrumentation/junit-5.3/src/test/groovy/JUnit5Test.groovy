import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.diff.FileDiff
import datadog.trace.civisibility.diff.LineDiff
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
import org.example.TestAssumption
import org.example.TestAssumptionAndSucceed
import org.example.TestAssumptionLegacy
import org.example.TestError
import org.example.TestFactory
import org.example.TestFailed
import org.example.TestFailedAndSucceed
import org.example.TestFailedFactory
import org.example.TestFailedParameterized
import org.example.TestFailedSuiteSetup
import org.example.TestFailedSuiteTearDown
import org.example.TestFailedTemplate
import org.example.TestFailedThenSucceed
import org.example.TestInheritance
import org.example.TestParameterized
import org.example.TestRepeated
import org.example.TestSkipped
import org.example.TestSkippedClass
import org.example.TestSkippedNested
import org.example.TestSucceed
import org.example.TestSucceedAndSkipped
import org.example.TestSucceedExpectedException
import org.example.TestSucceedNested
import org.example.TestSucceedSlow
import org.example.TestSucceedUnskippable
import org.example.TestSucceedUnskippableSuite
import org.example.TestSucceedVerySlow
import org.example.TestSucceedWithCategories
import org.example.TestSuiteSetUpAssumption
import org.example.TestTemplate
import org.junit.jupiter.engine.JupiterTestEngine
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit5Test extends CiVisibilityInstrumentationTest {

  def "test #testcaseName"() {
    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                                         | tests
    "test-succeed"                                       | [TestSucceed]
    "test-inheritance"                                   | [TestInheritance]
    "test-parameterized"                                 | [TestParameterized]
    "test-repeated"                                      | [TestRepeated]
    "test-template"                                      | [TestTemplate]
    "test-factory"                                       | [TestFactory]
    "test-failed"                                        | [TestFailed]
    "test-error"                                         | [TestError]
    "test-skipped"                                       | [TestSkipped]
    "test-skipped-class"                                 | [TestSkippedClass]
    "test-assumption-failed"                             | [TestAssumption]
    "test-assumption-failed-legacy"                      | [TestAssumptionLegacy]
    "test-succeed-and-skipped"                           | [TestSucceedAndSkipped]
    "test-succeed-and-failed"                            | [TestFailedAndSucceed]
    "test-suite-teardown-failed"                         | [TestFailedSuiteTearDown]
    "test-suite-setup-failed"                            | [TestFailedSuiteSetup]
    "test-categories"                                    | [TestSucceedWithCategories]
    "test-suite-setup-assumption-failed"                 | [TestSuiteSetUpAssumption]
    "test-suite-setup-assumption-failed-multi-test-case" | [TestAssumptionAndSucceed]
    "test-succeed-multiple-suites"                       | [TestSucceed, TestSucceedAndSkipped]
    "test-succeed-and-failed-multiple-suites"            | [TestSucceed, TestFailedAndSucceed]
    "test-succeed-nested-suites"                         | [TestSucceedNested]
    "test-skipped-nested-suites"                         | [TestSkippedNested]
  }

  def "test ITR #testcaseName"() {
    givenSkippableTests(skippedTests)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                       | tests                         | skippedTests
    "test-itr-skipping"                | [TestFailedAndSucceed]        | [
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_another_succeed", null),
      new TestIdentifier("org.example.TestFailedAndSucceed", "test_failed", null)
    ]
    "test-itr-skipping-parametrized"   | [TestParameterized]           | [
      new TestIdentifier("org.example.TestParameterized", "test_parameterized", '{"metadata":{"test_name":"[1] 0, 0, 0, some:\\\"parameter\\\""}}')
    ]
    "test-itr-unskippable"             | [TestSucceedUnskippable]      | [new TestIdentifier("org.example.TestSucceedUnskippable", "test_succeed", null)]
    "test-itr-unskippable-suite"       | [TestSucceedUnskippableSuite] | [new TestIdentifier("org.example.TestSucceedUnskippableSuite", "test_succeed", null)]
    "test-itr-unskippable-not-skipped" | [TestSucceedUnskippable]      | []
  }

  def "test flaky retries #testcaseName"() {
    givenFlakyRetryEnabled(true)
    givenFlakyTests(retriedTests)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                             | tests                          | retriedTests
    "test-failed"                            | [TestFailed]                   | []
    "test-retry-failed"                      | [TestFailed]                   | [new TestIdentifier("org.example.TestFailed", "test_failed", null)]
    "test-failed-then-succeed"               | [TestFailedThenSucceed]        | [new TestIdentifier("org.example.TestFailedThenSucceed", "test_failed_then_succeed", null)]
    "test-retry-template"                    | [TestFailedTemplate]           | [new TestIdentifier("org.example.TestFailedTemplate", "test_template", null)]
    "test-retry-factory"                     | [TestFailedFactory]            | [new TestIdentifier("org.example.TestFailedFactory", "test_factory", null)]
    "test-assumption-is-not-retried"         | [TestAssumption]               | [new TestIdentifier("org.example.TestAssumption", "test_fail_assumption", null)]
    "test-skipped-is-not-retried"            | [TestSkipped]                  | [new TestIdentifier("org.example.TestSkipped", "test_skipped", null)]
    "test-retry-parameterized"               | [TestFailedParameterized]      | [new TestIdentifier("org.example.TestFailedParameterized", "test_failed_parameterized", null)]
    "test-expected-exception-is-not-retried" | [TestSucceedExpectedException] | [new TestIdentifier("org.example.TestSucceedExpectedException", "test_succeed", null)]
  }

  def "test early flakiness detection #testcaseName"() {
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                        | tests                  | knownTestsList
    "test-efd-known-test"               | [TestSucceed]          | [new TestIdentifier("org.example.TestSucceed", "test_succeed", null)]
    "test-efd-known-parameterized-test" | [TestParameterized]    | [new TestIdentifier("org.example.TestParameterized", "test_parameterized", null)]
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

  private static void runTests(List<Class<?>> tests) {
    TestEventsHandlerHolder.startForcefully()

    DiscoverySelector[] selectors = new DiscoverySelector[tests.size()]
    for (i in 0..<tests.size()) {
      selectors[i] = selectClass(tests[i])
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
    try {
      launcher.execute(launcherReq)
    } catch (Throwable ignored) {
    }

    TestEventsHandlerHolder.stop()
  }

  @Override
  String instrumentedLibraryName() {
    return "junit5"
  }

  @Override
  String instrumentedLibraryVersion() {
    return JupiterTestEngine.getPackage().getImplementationVersion()
  }
}
