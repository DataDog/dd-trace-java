import datadog.trace.api.DisableTestTrace
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.junit5.JUnitPlatformUtils
import datadog.trace.instrumentation.junit5.TestEventsHandlerHolder
import org.example.*
import org.junit.jupiter.api.Assumptions
import org.junit.jupiter.api.ClassOrderer
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.engine.Constants
import org.junit.jupiter.engine.JupiterTestEngine
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.launcher.TestExecutionListener
import org.junit.platform.launcher.core.LauncherConfig
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder
import org.junit.platform.launcher.core.LauncherFactory

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit58Test extends CiVisibilityInstrumentationTest {

  def "test setup teardown methods #testcaseName"() {
    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                  | success | tests
    "test-before-each-after-each" | true    | [TestSucceedBeforeEachAfterEach]
    "test-before-all-after-all"   | true    | [TestSucceedBeforeAllAfterAll]
    "test-failed-before-all"      | false   | [TestFailedBeforeAll]
    "test-failed-after-all"       | false   | [TestFailedAfterAll]
    "test-failed-before-each"     | false   | [TestFailedBeforeEach]
    "test-failed-after-each"      | false   | [TestFailedAfterEach]
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

  def "test capabilities tagging #testcaseName"() {
    setup:
    Assumptions.assumeTrue(JUnitPlatformUtils.isJunitTestOrderingSupported(instrumentedLibraryVersion()))
    runTests([TestSucceed], true)

    expect:
    assertCapabilities(JUnitPlatformUtils.JUNIT_CAPABILITIES_ORDERING, 5)
  }

  private static void runTests(List<Class<?>> tests, boolean expectSuccess = true) {
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
    def listener = new TestResultListener()
    launcher.registerTestExecutionListeners(listener)
    try {
      launcher.execute(launcherReq)

      def failedTests = listener.testsByStatus[TestExecutionResult.Status.FAILED]
      if (expectSuccess) {
        if (failedTests != null && !failedTests.isEmpty()) {
          throw new AssertionError("Expected successful execution, the following tests were reported as failed: " + failedTests)
        }
      } else {
        if (failedTests == null || failedTests.isEmpty()) {
          throw new AssertionError("Expected a failed execution, got no failed tests")
        }
      }
    } finally {
      TestEventsHandlerHolder.stop()
    }
  }

  @Override
  String instrumentedLibraryName() {
    return "junit5"
  }

  @Override
  String instrumentedLibraryVersion() {
    return JupiterTestEngine.getPackage().getImplementationVersion()
  }

  private static final class TestResultListener implements TestExecutionListener {
    private final Map<TestExecutionResult.Status, Collection<org.junit.platform.launcher.TestIdentifier>> testsByStatus = new ConcurrentHashMap<>()

    void executionFinished(org.junit.platform.launcher.TestIdentifier testIdentifier, TestExecutionResult testExecutionResult) {
      testsByStatus.computeIfAbsent(testExecutionResult.status, k -> new CopyOnWriteArrayList<>()).add(testIdentifier)
    }
  }
}
