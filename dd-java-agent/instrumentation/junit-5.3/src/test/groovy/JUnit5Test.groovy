import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.SkippableTest
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
import org.example.TestAssumption
import org.example.TestAssumptionAndSucceed
import org.example.TestAssumptionLegacy
import org.example.TestError
import org.example.TestFactory
import org.example.TestFailed
import org.example.TestFailedAndSucceed
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
import org.example.TestSucceedUnskippable
import org.example.TestSucceedUnskippableSuite
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
    setup:
    givenSkippableTests(skippedTests)
    runTests(tests)

    expect:
    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                                         | tests                                | expectedTracesCount | skippedTests
    "test-succeed"                                       | [TestSucceed]                        | 2                   | []
    "test-inheritance"                                   | [TestInheritance]                    | 2                   | []
    "test-parameterized"                                 | [TestParameterized]                  | 3                   | []
    "test-repeated"                                      | [TestRepeated]                       | 3                   | []
    "test-template"                                      | [TestTemplate]                       | 3                   | []
    "test-factory"                                       | [TestFactory]                        | 3                   | []
    "test-failed"                                        | [TestFailed]                         | 2                   | []
    "test-error"                                         | [TestError]                          | 2                   | []
    "test-skipped"                                       | [TestSkipped]                        | 2                   | []
    "test-skipped-class"                                 | [TestSkippedClass]                   | 5                   | []
    "test-assumption-failed"                             | [TestAssumption]                     | 2                   | []
    "test-assumption-failed-legacy"                      | [TestAssumptionLegacy]               | 2                   | []
    "test-succeed-and-skipped"                           | [TestSucceedAndSkipped]              | 3                   | []
    "test-succeed-and-failed"                            | [TestFailedAndSucceed]               | 4                   | []
    "test-suite-teardown-failed"                         | [TestFailedSuiteTearDown]            | 3                   | []
    "test-suite-setup-failed"                            | [TestFailedSuiteTearDown]            | 1                   | []
    "test-categories"                                    | [TestSucceedWithCategories]          | 2                   | []
    "test-suite-setup-assumption-failed"                 | [TestSuiteSetUpAssumption]           | 2                   | []
    "test-suite-setup-assumption-failed-multi-test-case" | [TestAssumptionAndSucceed]           | 3                   | []
    "test-succeed-multiple-suites"                       | [TestSucceed, TestSucceedAndSkipped] | 4                   | []
    "test-succeed-and-failed-multiple-suites"            | [TestSucceed, TestFailedAndSucceed]  | 5                   | []
    "test-succeed-nested-suites"                         | [TestSucceedNested]                  | 3                   | []
    "test-skipped-nested-suites"                         | [TestSkippedNested]                  | 3                   | []
    "test-itr-skipping"                                  | [TestFailedAndSucceed]               | 4                   | [
      new SkippableTest("org.example.TestFailedAndSucceed", "test_another_succeed", null, null),
      new SkippableTest("org.example.TestFailedAndSucceed", "test_failed", null, null)
    ]
    "test-itr-skipping-parametrized"                     | [TestParameterized]                  | 3                   | [
      new SkippableTest("org.example.TestParameterized", "test_parameterized", '{"metadata":{"test_name":"[1] 0, 0, 0, some:\\\"parameter\\\""}}', null)
    ]
    "test-itr-unskippable"                               | [TestSucceedUnskippable]             | 2                   | [new SkippableTest("org.example.TestSucceedUnskippable", "test_succeed", null, null)]
    "test-itr-unskippable-suite"                         | [TestSucceedUnskippableSuite]        | 2                   | [new SkippableTest("org.example.TestSucceedUnskippableSuite", "test_succeed", null, null)]
    "test-itr-unskippable-not-skipped"                   | [TestSucceedUnskippable]             | 2                   | []
  }

  private static void runTests(List<Class<?>> tests) {
    TestEventsHandlerHolder.start()

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
