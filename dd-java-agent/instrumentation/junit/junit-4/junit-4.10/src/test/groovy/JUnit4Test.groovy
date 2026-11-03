import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.diff.LineDiff
import datadog.trace.instrumentation.junit4.JUnit4Utils
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder
import org.example.*
import org.junit.jupiter.api.Assumptions
import org.junit.experimental.ParallelComputer
import org.junit.runner.Computer
import org.junit.runner.JUnitCore
import org.junit.runner.Request
import org.junit.runner.Result
import org.junit.runner.notification.RunNotifier

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@DisableTestTrace(reason = "avoid self-tracing")
class JUnit4Test extends CiVisibilityInstrumentationTest {

  def runner = new JUnitCore()

  def "test runner without suite lifecycle events #testcaseName"() {
    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                                  | success | tests
    "test-custom-runner"                          | false   | [TestSucceedCustomRunner]
    "test-custom-runner-suite-failure"            | false   | [TestFailedSuiteCustomRunner]
    "test-custom-runner-suite-assumption-failure" | true    | [TestSkippedSuiteCustomRunner]
  }

  def "test multiple runners without suite lifecycle events #mode"() {
    given:
    def initialSpan = AgentTracer.activeSpan()

    when:
    def result = runTestClasses(computer, tests)
    def events = getEventsAsJson(TEST_WRITER.toList())
    def suites = events.findAll { it.type == "test_suite_end" }

    then:
    result.runCount == 4
    result.ignoreCount == 2
    result.failures*.description*.methodName == ["existing failure", "existing failure"]
    result.failures*.exception*.class == [AssertionError, AssertionError]
    suites*.content*.meta*.get("test.suite").sort() == [TestSucceedCustomRunner.name, TestSucceedCustomRunner.AnotherSuite.name].sort()
    suites.every { suite -> testStatuses(events, suite) == ["adds": "pass", "existing failure": "fail", "ignored": "skip"] }
    AgentTracer.activeSpan() == initialSpan

    where:
    mode                  | computer                          | tests
    "parallel"            | new ParallelComputer(true, false) | [TestSucceedCustomRunner, TestSucceedCustomRunner.AnotherSuite]
    "sequential"          | new Computer()                    | [TestSucceedCustomRunner, TestSucceedCustomRunner.AnotherSuite]
    "sequential reversed" | new Computer()                    | [TestSucceedCustomRunner.AnotherSuite, TestSucceedCustomRunner]
  }

  def "test executor callbacks without suite lifecycle events"() {
    given:
    def executor = Executors.newSingleThreadExecutor()
    TestSucceedExecutorRunner.executor = executor
    def initialSpan = AgentTracer.activeSpan()
    def initialWorkerSpan = CompletableFuture.supplyAsync({ AgentTracer.activeSpan() }, executor).join()

    when:
    def result = runTestClasses(new Computer(), [TestSucceedExecutorRunner])
    def workerSpan = CompletableFuture.supplyAsync({ AgentTracer.activeSpan() }, executor).join()
    def events = getEventsAsJson(TEST_WRITER.toList())
    def suites = events.findAll { it.type == "test_suite_end" }

    then:
    workerSpan == initialWorkerSpan
    AgentTracer.activeSpan() == initialSpan
    result.runCount == 2
    result.ignoreCount == 1
    result.failures*.description*.methodName == ["existing failure"]
    result.failures*.exception*.class == [AssertionError]
    suites*.content*.meta*.get("test.suite") == [TestSucceedExecutorRunner.name]
    testStatuses(events, suites[0]) == ["adds": "pass", "existing failure": "fail", "ignored": "skip"]

    cleanup:
    executor.shutdownNow()
    TestSucceedExecutorRunner.executor = null
  }

  def "test concurrent runners sharing a notifier"() {
    given:
    TestSucceedConcurrentRunners.firstStarted = new CountDownLatch(1)
    TestSucceedConcurrentRunners.secondStarted = new CountDownLatch(1)
    TestSucceedConcurrentRunners.firstFinished = new CountDownLatch(1)
    def notifier = new RunNotifier()

    when:
    TestEventsHandlerHolder.start(TestFrameworkInstrumentation.JUNIT4, JUnit4Utils.capabilities(false))
    try {
      def first = Thread.start {
        Request.aClass(TestSucceedConcurrentRunners.First).getRunner().run(notifier)
        TestSucceedConcurrentRunners.firstFinished.countDown()
      }
      TestSucceedConcurrentRunners.firstStarted.await(10, TimeUnit.SECONDS)
      def second = Thread.start {
        Request.aClass(TestSucceedConcurrentRunners.Second).getRunner().run(notifier)
      }
      first.join()
      second.join()
    } finally {
      TestEventsHandlerHolder.stop(TestFrameworkInstrumentation.JUNIT4)
    }
    def events = getEventsAsJson(TEST_WRITER.toList())
    def suites = events.findAll { it.type == "test_suite_end" }

    then:
    suites*.content*.meta*.get("test.suite").sort() == [TestSucceedConcurrentRunners.First.name, TestSucceedConcurrentRunners.Second.name]
    testStatuses(events, suites.find { it.content.meta["test.suite"] == TestSucceedConcurrentRunners.Second.name }) == ["test_a_outlives_first": "pass", "test_b_after_first": "pass"]
  }

  def "test #testcaseName"() {
    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                                         | success | tests
    "test-succeed"                                       | true    | [TestSucceed]
    "test-inheritance"                                   | true    | [TestInheritance]
    "test-failed"                                        | false   | [TestFailed]
    "test-error"                                         | false   | [TestError]
    "test-skipped"                                       | true    | [TestSkipped]
    "test-class-skipped"                                 | true    | [TestSkippedClass]
    "test-success-and-skipped"                           | true    | [TestSucceedAndSkipped]
    "test-success-and-failure"                           | false   | [TestFailedAndSucceed]
    "test-suite-teardown-failure"                        | false   | [TestFailedSuiteTearDown]
    "test-suite-setup-failure"                           | false   | [TestFailedSuiteSetup]
    "test-assumption-failure"                            | true    | [TestAssumption]
    "test-categories-are-included-in-spans"              | true    | [TestSucceedWithCategories]
    "test-assumption-failure-during-suite-setup"         | true    | [TestFailedSuiteSetUpAssumption]
    "test-assumption-failure-in-a-multi-test-case-suite" | true    | [TestAssumptionAndSucceed]
    "test-multiple-successful-suites"                    | true    | [TestSucceed, TestSucceedAndSkipped]
    "test-successful-suite-and-failing-suite"            | false   | [TestSucceed, TestFailedAndSucceed]
    "test-parameterized"                                 | true    | [TestParameterized]
    "test-suite-runner"                                  | true    | [TestSucceedSuite]
    "test-legacy"                                        | true    | [TestSucceedLegacy]
    "test-parameterized-junit-params"                    | true    | [TestParameterizedJUnitParams]
    "test-succeed-kotlin"                                | true    | [TestSucceedKotlin]
    "test-succeed-parameterized-kotlin"                  | true    | [TestSucceedParameterizedKotlin]
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
    "test-itr-skipping-parameterized"  | [TestParameterized]           | [
      new TestIdentifier("org.example.TestParameterized", "parameterized_test_succeed", '{"metadata":{"test_name":"parameterized_test_succeed[str1]"}}')
    ]
    "test-itr-unskippable"             | [TestSucceedUnskippable]      | [new TestIdentifier("org.example.TestSucceedUnskippable", "test_succeed", null)]
    "test-itr-unskippable-suite"       | [TestSucceedUnskippableSuite] | [new TestIdentifier("org.example.TestSucceedUnskippableSuite", "test_succeed", null)]
    "test-itr-unskippable-not-skipped" | [TestSucceedUnskippable]      | []
  }

  def "test flaky retries #testcaseName"() {
    givenFlakyRetryEnabled(true)
    givenFlakyTests(retriedTests)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                             | success | tests                          | retriedTests
    "test-failed"                            | false   | [TestFailed]                   | []
    "test-retry-failed"                      | false   | [TestFailed]                   | [new TestFQN("org.example.TestFailed", "test_failed")]
    "test-failed-then-succeed"               | true    | [TestFailedThenSucceed]        | [new TestFQN("org.example.TestFailedThenSucceed", "test_failed_then_succeed")]
    "test-assumption-is-not-retried"         | true    | [TestAssumption]               | [new TestFQN("org.example.TestAssumption", "test_fail_assumption")]
    "test-skipped-is-not-retried"            | true    | [TestSkipped]                  | [new TestFQN("org.example.TestSkipped", "test_skipped")]
    "test-retry-parameterized"               | false   | [TestFailedParameterized]      | [
      new TestFQN("org.example.TestFailedParameterized", "test_failed_parameterized") /* backend cannot provide parameters for flaky parameterized tests yet */
    ]
    "test-expected-exception-is-not-retried" | true    | [TestSucceedExpectedException] | [new TestFQN("org.example.TestSucceedExpectedException", "test_succeed")]
  }

  def "test early flakiness detection #testcaseName"() {
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                        | success | tests                  | knownTestsList
    "test-efd-known-test"               | true    | [TestSucceed]          | [new TestFQN("org.example.TestSucceed", "test_succeed")]
    "test-efd-known-parameterized-test" | true    | [TestParameterized]    | [new TestFQN("org.example.TestParameterized", "parameterized_test_succeed")]
    "test-efd-new-test"                 | true    | [TestSucceed]          | []
    "test-efd-new-parameterized-test"   | true    | [TestParameterized]    | []
    "test-efd-known-tests-and-new-test" | false   | [TestFailedAndSucceed] | [
      new TestFQN("org.example.TestFailedAndSucceed", "test_failed"),
      new TestFQN("org.example.TestFailedAndSucceed", "test_succeed")
    ]
    "test-efd-new-slow-test"            | true    | [TestSucceedSlow]      | [] // is executed only twice
    "test-efd-new-very-slow-test"       | true    | [TestSucceedVerySlow]  | [] // is executed only once
    "test-efd-faulty-session-threshold" | false   | [TestFailedAndSucceed] | []
    "test-efd-skip-new-test"            | true    | [TestSucceedSkipEfd]   | []
  }

  def "test impacted tests detection #testcaseName"() {
    givenImpactedTestsDetectionEnabled(true)
    givenDiff(prDiff)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName            | tests         | prDiff
    "test-succeed"          | [TestSucceed] | LineDiff.EMPTY
    "test-succeed"          | [TestSucceed] | new LineDiff([(DUMMY_SOURCE_PATH): lines()])
    "test-succeed-impacted" | [TestSucceed] | new LineDiff([(DUMMY_SOURCE_PATH): lines(DUMMY_TEST_METHOD_START)])
  }

  def "test quarantined #testcaseName"() {
    givenQuarantinedTests(quarantined)

    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                            | tests                     | quarantined
    "test-quarantined-failed"               | [TestFailed]              | [new TestFQN("org.example.TestFailed", "test_failed")]
    "test-quarantined-failed-parameterized" | [TestFailedParameterized] | [new TestFQN("org.example.TestFailedParameterized", "test_failed_parameterized")]
  }

  def "test quarantined auto-retries #testcaseName"() {
    givenQuarantinedTests(quarantined)

    givenFlakyRetryEnabled(true)
    givenFlakyTests(retried)

    // every test retry fails, but the build status is successful
    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                  | tests        | quarantined                                            | retried
    "test-quarantined-failed-atr" | [TestFailed] | [new TestFQN("org.example.TestFailed", "test_failed")] | [new TestFQN("org.example.TestFailed", "test_failed")]
  }

  def "test quarantined early flakiness detection #testcaseName"() {
    givenQuarantinedTests(quarantined)

    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(known)

    // every retry fails, but the build status is successful
    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                    | tests        | quarantined                                            | known
    "test-quarantined-failed-known" | [TestFailed] | [new TestFQN("org.example.TestFailed", "test_failed")] | [new TestFQN("org.example.TestFailed", "test_failed")]
    "test-quarantined-failed-efd"   | [TestFailed] | [new TestFQN("org.example.TestFailed", "test_failed")] | []
  }

  def "test disabled #testcaseName"() {
    givenDisabledTests(disabled)

    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                         | tests                     | disabled
    "test-disabled-failed"               | [TestFailed]              | [new TestFQN("org.example.TestFailed", "test_failed")]
    "test-disabled-failed-parameterized" | [TestFailedParameterized] | [new TestFQN("org.example.TestFailedParameterized", "test_failed_parameterized")]
  }

  def "test attempt to fix #testcaseName"() {
    givenQuarantinedTests(quarantined)
    givenDisabledTests(disabled)
    givenAttemptToFixTests(attemptToFix)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                                | success | tests         | attemptToFix                                             | quarantined                                              | disabled
    "test-attempt-to-fix-failed"                | false   | [TestFailed]  | [new TestFQN("org.example.TestFailed", "test_failed")]   | []                                                       | []
    "test-attempt-to-fix-succeeded"             | true    | [TestSucceed] | [new TestFQN("org.example.TestSucceed", "test_succeed")] | []                                                       | []
    "test-attempt-to-fix-quarantined-failed"    | false   | [TestFailed]  | [new TestFQN("org.example.TestFailed", "test_failed")]   | [new TestFQN("org.example.TestFailed", "test_failed")]   | []
    "test-attempt-to-fix-quarantined-succeeded" | true    | [TestSucceed] | [new TestFQN("org.example.TestSucceed", "test_succeed")] | [new TestFQN("org.example.TestSucceed", "test_succeed")] | []
    "test-attempt-to-fix-disabled-failed"       | false   | [TestFailed]  | [new TestFQN("org.example.TestFailed", "test_failed")]   | []                                                       | [new TestFQN("org.example.TestFailed", "test_failed")]
    "test-attempt-to-fix-disabled-succeeded"    | true    | [TestSucceed] | [new TestFQN("org.example.TestSucceed", "test_succeed")] | []                                                       | [new TestFQN("org.example.TestSucceed", "test_succeed")]
  }

  def "test capabilities tagging #testcaseName"() {
    setup:
    Assumptions.assumeFalse(JUnit4Utils.isTestOrderingSupported(JUnit4Utils.getVersion()))
    runTests([TestSucceed], true)

    expect:
    assertCapabilities(JUnit4Utils.BASE_CAPABILITIES, 4)
  }

  private void runTests(Collection<Class<?>> tests, boolean expectSuccess = true) {
    def result = runTestClasses(new Computer(), tests)
    if (expectSuccess) {
      if (result.getFailureCount() > 0) {
        throw new AssertionError("Expected successful execution, got following failures: " + result.getFailures())
      }
    } else {
      if (result.getFailureCount() == 0) {
        throw new AssertionError("Expected a failed execution, got no failures")
      }
    }
  }

  private Result runTestClasses(Computer computer, Collection<Class<?>> tests) {
    TestEventsHandlerHolder.start(TestFrameworkInstrumentation.JUNIT4, JUnit4Utils.capabilities(false))
    try {
      return runner.run(computer, tests as Class[])
    } finally {
      TestEventsHandlerHolder.stop(TestFrameworkInstrumentation.JUNIT4)
    }
  }

  private static Map<String, String> testStatuses(List<Map> events, Map suite) {
    return events
      .findAll { it.type == "test" && it.content.test_suite_id == suite.content.test_suite_id }
      .collectEntries { [(it.content.meta["test.name"]): it.content.meta["test.status"]] }
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
