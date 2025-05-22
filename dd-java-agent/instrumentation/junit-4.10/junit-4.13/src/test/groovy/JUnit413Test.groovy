import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.LibraryCapability
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.junit4.JUnit4Utils
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder
import org.example.TestFailedAfter
import org.example.TestFailedAfterClass
import org.example.TestFailedAfterParam
import org.example.TestFailedBefore
import org.example.TestFailedBeforeClass
import org.example.TestFailedBeforeParam
import org.example.TestOrderer
import org.example.TestSorter
import org.example.TestSucceed
import org.example.TestSucceedBeforeAfter
import org.example.TestSucceedBeforeClassAfterClass
import org.example.TestSucceedBeforeParamAfterParam
import org.junit.jupiter.api.Assumptions
import org.junit.runner.JUnitCore

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit413Test extends CiVisibilityInstrumentationTest {

  def runner = new JUnitCore()

  def "test setup teardown methods #testcaseName"() {
    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                            | success | tests
    "test-succeed-before-after"             | true    | [TestSucceedBeforeAfter]
    "test-succeed-before-class-after-class" | true    | [TestSucceedBeforeClassAfterClass]
    "test-succeed-before-param-after-param" | true    | [TestSucceedBeforeParamAfterParam]
    "test-failed-before-class"              | false   | [TestFailedBeforeClass]
    "test-failed-after-class"               | false   | [TestFailedAfterClass]
    "test-failed-before"                    | false   | [TestFailedBefore]
    "test-failed-after"                     | false   | [TestFailedAfter]
    "test-failed-before-param"              | false   | [TestFailedBeforeParam]
    "test-failed-after-param"               | false   | [TestFailedAfterParam]
  }

  def "test known tests ordering #testcaseName"() {
    givenKnownTests(knownTestsList)

    runTests(tests)

    assertTestsOrder(expectedOrder)

    where:
    testcaseName       | tests         | knownTestsList                                      | expectedOrder
    "sorting-methods"  | [TestSorter]  | [test("org.example.TestSorter", "test_succeed_1")]  | [
      test("org.example.TestSorter", "test_succeed_2"),
      test("org.example.TestSorter", "test_succeed_3"),
      test("org.example.TestSorter", "test_succeed_1")
    ]
    "ordering-methods" | [TestOrderer] | [test("org.example.TestOrderer", "test_succeed_2")] | [
      test("org.example.TestOrderer", "test_succeed_3"),
      test("org.example.TestOrderer", "test_succeed_1"),
      test("org.example.TestOrderer", "test_succeed_2")
    ]
  }

  def "test flaky tests ordering #testcaseName"() {
    givenKnownTests(expectedOrder)
    givenFlakyTests(flakyTestList)

    runTests(tests)

    assertTestsOrder(expectedOrder)

    where:
    testcaseName       | tests         | flakyTestList                                                                                          | expectedOrder
    "sorting-methods"  | [TestSorter]  | [test("org.example.TestSorter", "test_succeed_2"), test("org.example.TestSorter", "test_succeed_3")]   | [
      test("org.example.TestSorter", "test_succeed_2"),
      test("org.example.TestSorter", "test_succeed_3"),
      test("org.example.TestSorter", "test_succeed_1")
    ]
    "ordering-methods" | [TestOrderer] | [
      test("org.example.TestOrderer", "test_succeed_1"),
      test("org.example.TestOrderer", "test_succeed_3")
    ] | [
      test("org.example.TestOrderer", "test_succeed_3"),
      test("org.example.TestOrderer", "test_succeed_1"),
      test("org.example.TestOrderer", "test_succeed_2")
    ]
  }

  def "test capabilities tagging #testcaseName"() {
    setup:
    Assumptions.assumeTrue(JUnit4Utils.isTestOrderingSupported(JUnit4Utils.getVersion()))
    runTests([TestSucceed], true)
    def capabilities = new ArrayList<>(JUnit4Utils.BASE_CAPABILITIES)
    capabilities.add(LibraryCapability.FAIL_FAST)

    expect:
    assertCapabilities(capabilities, 4)
  }

  private void runTests(Collection<Class<?>> tests, boolean expectSuccess = true) {
    TestEventsHandlerHolder.start(TestFrameworkInstrumentation.JUNIT4, JUnit4Utils.capabilities(false))
    try {
      Class[] array = tests.toArray(new Class[0])
      def result = runner.run(array)
      if (expectSuccess) {
        if (result.getFailureCount() > 0) {
          throw new AssertionError("Expected successful execution, got following failures: " + result.getFailures())
        }
      } else {
        if (result.getFailureCount() == 0) {
          throw new AssertionError("Expected a failed execution, got no failures")
        }
      }
    } finally {
      TestEventsHandlerHolder.stop(TestFrameworkInstrumentation.JUNIT4)
    }
  }

  @Override
  String instrumentedLibraryName() {
    return "junit4"
  }

  @Override
  String instrumentedLibraryVersion() {
    return JUnit4Utils.getVersion()
  }
}
