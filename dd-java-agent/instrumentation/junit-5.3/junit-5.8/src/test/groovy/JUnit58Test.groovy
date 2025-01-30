import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.CIConstants
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
import org.example.*
import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.engine.Constants
import org.junit.jupiter.engine.JupiterTestEngine
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit58Test extends CiVisibilityInstrumentationTest {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()
    givenTestsOrder(CIConstants.FAIL_FAST_TEST_ORDER)
  }

  def "test #testcaseName"() {
    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                  | tests
    "test-before-each-after-each" | [TestSucceedBeforeEachAfterEach]
    "test-before-all-after-all"   | [TestSucceedBeforeAllAfterAll]
    "test-failed-before-all"      | [TestFailedBeforeAll]
    "test-failed-after-all"       | [TestFailedAfterAll]
    "test-failed-before-each"     | [TestFailedBeforeEach]
    "test-failed-after-each"      | [TestFailedAfterEach]
  }

  def "test known tests ordering #testcaseName"() {
    givenKnownTests(knownTestsList)

    runTests(tests)

    assertTestsOrder(expectedOrder)

    where:
    testcaseName                     | tests                             | knownTestsList                                                        | expectedOrder
    "ordering-methods"               | [TestSucceed]                     | [test("org.example.TestSucceed", "test_succeed_1")]                   | [
      test("org.example.TestSucceed", "test_succeed_2"),
      test("org.example.TestSucceed", "test_succeed_1")
    ]
    "ordering-classes"               | [TestSucceed, TestSucceedAnother] | [test("org.example.TestSucceed", "test_succeed_1")]                   | [
      test("org.example.TestSucceedAnother", "test_succeed_1"),
      test("org.example.TestSucceed", "test_succeed_2"),
      test("org.example.TestSucceed", "test_succeed_1")
    ]
    "ordering-parameterized-methods" | [TestParameterized]               | [test("org.example.TestParameterized", "test_another_parameterized")] | [
      test("org.example.TestParameterized", "test_parameterized"),
      test("org.example.TestParameterized", "test_another_parameterized")
    ]
  }

  def "test flaky tests ordering #testcaseName"() {
    givenKnownTests(expectedOrder)
    givenFlakyTests(flakyTestsList)

    runTests(tests)

    assertTestsOrder(expectedOrder)

    where:
    testcaseName       | tests                             | flakyTestsList                                             | expectedOrder
    "ordering-methods" | [TestSucceed]                     | [test("org.example.TestSucceed", "test_succeed_2")]        | [
      test("org.example.TestSucceed", "test_succeed_2"),
      test("org.example.TestSucceed", "test_succeed_1")
    ]
    "ordering-classes" | [TestSucceed, TestSucceedAnother] | [test("org.example.TestSucceedAnother", "test_succeed_1")] | [
      test("org.example.TestSucceedAnother", "test_succeed_1"),
      test("org.example.TestSucceed", "test_succeed_1"),
      test("org.example.TestSucceed", "test_succeed_2")
    ]
  }

  private static void runTests(List<Class<?>> tests) {
    TestEventsHandlerHolder.startForcefully()

    DiscoverySelector[] selectors = new DiscoverySelector[tests.size()]
    for (i in 0..<tests.size()) {
      selectors[i] = selectClass(tests[i])
    }

    def launcherReq = LauncherDiscoveryRequestBuilder.request()
      .configurationParameter(Constants.DEFAULT_TEST_CLASS_ORDER_PROPERTY_NAME, ClassOrderer.ClassName.name)
      .configurationParameter(Constants.DEFAULT_TEST_METHOD_ORDER_PROPERTY_NAME, MethodOrderer.MethodName.name)
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
