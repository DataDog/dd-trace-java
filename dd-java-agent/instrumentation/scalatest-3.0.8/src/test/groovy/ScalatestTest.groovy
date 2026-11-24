import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.diff.LineDiff
import datadog.trace.instrumentation.scalatest.ScalatestUtils
import org.example.TestFailed
import org.example.TestAutomationExcluded
import org.example.TestAutomationNoRetry
import org.example.TestAutomationPreventSuppression
import org.example.TestAutomationRetryEligibility
import org.example.TestFailedParameterized
import org.example.TestFailedSuite
import org.example.TestFailedThenSucceed
import org.example.TestIgnored
import org.example.TestIgnoredAsync
import org.example.TestIgnoredCanceled
import org.example.TestIgnoredPending
import org.example.TestSucceed
import org.example.TestSucceedFlatSpec
import org.example.TestSucceedMoreCases
import org.example.TestSucceedParameterized
import org.example.TestSucceedSkipEfd
import org.example.TestSucceedSlow
import org.example.TestSucceedUnskippable
import org.scalatest.tools.Framework
import org.scalatest.tools.Runner
import sbt.testing.Event
import sbt.testing.EventHandler
import sbt.testing.Logger
import sbt.testing.Selector
import sbt.testing.Status
import sbt.testing.SubclassFingerprint
import sbt.testing.SuiteSelector
import sbt.testing.Task
import sbt.testing.TaskDef

class ScalatestTest extends CiVisibilityInstrumentationTest {

  def "test automation exclusions preserve a blocking failure and execute once"() {
    given:
    TestAutomationExcluded.executions().set(0)
    TestAutomationExcluded.skippedExecutions().set(0)
    def id = new TestFQN("org.example.TestAutomationExcluded", "protected_failure")
    givenDisabledTests([id])
    givenQuarantinedTests([id])
    givenAttemptToFixTests([id])
    givenFlakyRetryEnabled(true)
    givenFlakyTests([id])
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests([])

    when:
    runTests([TestAutomationExcluded], false)

    then:
    TestAutomationExcluded.executions().get() == 1
    TestAutomationExcluded.skippedExecutions().get() == 0
    TEST_WRITER.toList().flatten().count { it.getTag("test.name") == "protected_failure" } == 1
    def testSpan = TEST_WRITER.toList().flatten().find { it.getTag("test.name") == "protected_failure" }
    testSpan != null
    testSpan.getTag("test.test_management.no_auto_retry") == true
    testSpan.getTag("test.test_management.prevent_failure_suppression") == true
    testSpan.getTag("test.test_management.is_test_disabled") == true
    testSpan.getTag("test.test_management.is_quarantined") == true
    testSpan.getTag("test.test_management.is_attempt_to_fix") == true
    testSpan.getTag("test.test_management.attempt_to_fix_passed") == null
    def skippedSpan = TEST_WRITER.toList().flatten().find { it.getTag("test.name") == "protected_skip" }
    skippedSpan != null
    skippedSpan.getTag("test.test_management.no_auto_retry") == true
    skippedSpan.getTag("test.test_management.prevent_failure_suppression") == true
    skippedSpan.getTag("test.status").toString() == "skip"
  }


  def "test automation no-auto-retry alone preserves quarantine suppression"() {
    given:
    TestAutomationNoRetry.executions().set(0)
    def id = new TestFQN("org.example.TestAutomationNoRetry", "no_retry_failure")
    givenQuarantinedTests([id])
    givenAttemptToFixTests([id])
    givenFlakyRetryEnabled(true)
    givenFlakyTests([id])
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests([])

    when:
    runTests([TestAutomationNoRetry], true)

    then:
    TestAutomationNoRetry.executions().get() == 1
    def testSpans = TEST_WRITER.toList().flatten().findAll { it.getTag("test.name") == "no_retry_failure" }
    testSpans.size() == 1
    def testSpan = testSpans.first()
    testSpan.getTag("test.status").toString() == "fail"
    testSpan.getTag("test.test_management.no_auto_retry") == true
    testSpan.getTag("test.test_management.prevent_failure_suppression") == null
    testSpan.getTag("test.test_management.is_quarantined") == true
    testSpan.getTag("test.test_management.is_attempt_to_fix") == true
    testSpan.getTag("test.test_management.attempt_to_fix_passed") == null
  }

  def "test automation failure protection alone preserves automatic retries"() {
    given:
    TestAutomationPreventSuppression.executions().set(0)
    def id = new TestFQN("org.example.TestAutomationPreventSuppression", "blocking_failure")
    givenDisabledTests([id])
    givenQuarantinedTests([id])
    givenFlakyRetryEnabled(true)
    givenFlakyTests([id])

    when:
    runTests([TestAutomationPreventSuppression], false)

    then:
    TestAutomationPreventSuppression.executions().get() == 5
    def testSpans = TEST_WRITER.toList().flatten().findAll { it.getTag("test.name") == "blocking_failure" }
    testSpans.size() == 5 // Java's default ATR budget includes the initial execution.
    testSpans.count { it.getTag("test.is_retry") == true } == 4
    testSpans.count { it.getTag("test.final_status")?.toString() == "fail" } == 1
    testSpans.every { it.getTag("test.status").toString() == "fail" }
    testSpans.every { it.getTag("test.test_management.no_auto_retry") == null }
    testSpans.every { it.getTag("test.test_management.prevent_failure_suppression") == true }
    testSpans.every { it.getTag("test.test_management.is_test_disabled") == true }
    testSpans.every { it.getTag("test.test_management.is_quarantined") == true }
  }


  def "test automation no-auto-retry excludes #feature while the native control repeats"() {
    given:
    TestAutomationRetryEligibility.excludedExecutions().set(0)
    TestAutomationRetryEligibility.controlExecutions().set(0)
    def excludedId = new TestFQN("org.example.TestAutomationRetryEligibility", "excluded_pass")
    def controlId = new TestFQN("org.example.TestAutomationRetryEligibility", "control_pass")
    if (feature == "EFD") {
      givenEarlyFlakinessDetectionEnabled(true)
      givenKnownTests([new TestFQN("org.example.OtherSuite", "known")])
    } else {
      givenKnownTests([excludedId, controlId])
      givenAttemptToFixTests([excludedId, controlId])
    }

    when:
    runTests([TestAutomationRetryEligibility], true)

    then:
    TestAutomationRetryEligibility.excludedExecutions().get() == 1
    TestAutomationRetryEligibility.controlExecutions().get() == controlExecutions
    def testSpans = TEST_WRITER.toList().flatten()
    def excluded = testSpans.findAll { it.getTag("test.name") == "excluded_pass" }
    def control = testSpans.findAll { it.getTag("test.name") == "control_pass" }
    excluded.size() == 1
    control.size() == controlExecutions
    excluded.first().getTag("test.status").toString() == "pass"
    excluded.first().getTag("test.test_management.no_auto_retry") == true
    excluded.first().getTag("test.test_management.prevent_failure_suppression") == null
    excluded.first().getTag("test.is_retry") == null
    excluded.first().getTag("test.retry_reason") == null
    excluded.first().getTag("test.test_management.attempt_to_fix_passed") == null
    control.every { it.getTag("test.test_management.no_auto_retry") == null }
    control.count { it.getTag("test.is_retry") == true } == controlExecutions - 1
    if (feature == "EFD") {
      assert excluded.first().getTag("test.is_new") == true
    } else {
      assert excluded.first().getTag("test.test_management.is_attempt_to_fix") == true
    }

    where:
    feature         | controlExecutions
    "EFD"           | 3
    "Attempt to Fix" | 5
  }

  def "test #testcaseName"() {
    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                 | success | tests
    "test-succeed"               | true    | [TestSucceed]
    "test-succeed-flat-spec"     | true    | [TestSucceedFlatSpec]
    "test-succeed-parameterized" | true    | [TestSucceedParameterized]
    "test-failed"                | false   | [TestFailed]
    "test-ignored"               | true    | [TestIgnored]
    "test-canceled"              | true    | [TestIgnoredCanceled]
    "test-pending"               | true    | [TestIgnoredPending]
    "test-failed-suite"          | false   | [TestFailedSuite]
  }

  def "test ITR #testcaseName"() {
    givenSkippableTests(skippedTests)
    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                       | tests                    | skippedTests
    "test-itr-skipping"                | [TestSucceed]            | [new TestIdentifier("org.example.TestSucceed", "Example.add adds two numbers", null)]
    "test-itr-unskippable"             | [TestSucceedUnskippable] | [new TestIdentifier("org.example.TestSucceedUnskippable", "test should assert something", null)]
    "test-itr-unskippable-not-skipped" | [TestSucceedUnskippable] | []
  }

  def "test flaky retries #testcaseName"() {
    givenFlakyRetryEnabled(true)
    givenFlakyTests(retriedTests)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName               | success | tests                     | retriedTests
    "test-failed"              | false   | [TestFailed]              | []
    "test-retry-failed"        | false   | [TestFailed]              | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")]
    "test-retry-parameterized" | false   | [TestFailedParameterized] | [
      new TestFQN("org.example.TestFailedParameterized", "addition should correctly add two numbers")
    ]
    "test-failed-then-succeed" | true    | [TestFailedThenSucceed]   | [new TestFQN("org.example.TestFailedThenSucceed", "Example.add adds two numbers")]
  }

  def "test flaky retries with sbt framework #testcaseName"() {
    givenFlakyRetryEnabled(true)

    runTestsWithSbtFramework(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                          | success | tests
    "test-sbt-async-ignored-then-succeed" | true    | [TestIgnoredAsync, TestSucceed]
  }

  def "test early flakiness detection #testcaseName"() {
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                        | tests                  | knownTestsList
    "test-efd-known-test"               | [TestSucceed]          | [new TestFQN("org.example.TestSucceed", "Example.add adds two numbers")]
    "test-efd-new-test"                 | [TestSucceed]          | []
    "test-efd-new-slow-test"            | [TestSucceedSlow]      | [] // is executed only twice
    "test-efd-faulty-session-threshold" | [TestSucceedMoreCases] | []
    "test-efd-skip-new-test"            | [TestSucceedSkipEfd]   | []
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
    testcaseName              | tests        | quarantined
    "test-quarantined-failed" | [TestFailed] | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")]
  }

  def "test quarantined auto-retries #testcaseName"() {
    givenQuarantinedTests(quarantined)

    givenFlakyRetryEnabled(true)
    givenFlakyTests(retried)

    // every test retry fails, but the build status is successful
    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                  | tests        | quarantined                                                             | retried
    "test-quarantined-failed-atr" | [TestFailed] | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")] | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")]
  }

  def "test quarantined early flakiness detection #testcaseName"() {
    givenQuarantinedTests(quarantined)

    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(known)

    // every retry fails, but the build status is successful
    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                    | tests        | quarantined                                                             | known
    "test-quarantined-failed-known" | [TestFailed] | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")] | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")]
    "test-quarantined-failed-efd"   | [TestFailed] | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")] | []
  }

  def "test disabled #testcaseName"() {
    givenDisabledTests(disabled)

    runTests(tests, true)

    assertSpansData(testcaseName)

    where:
    testcaseName           | tests        | disabled
    "test-disabled-failed" | [TestFailed] | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")]
  }

  def "test attempt to fix #testcaseName"() {
    givenQuarantinedTests(quarantined)
    givenDisabledTests(disabled)
    givenAttemptToFixTests(attemptToFix)

    runTests(tests, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                                | success | tests         | attemptToFix                                                             | quarantined                                                              | disabled
    "test-attempt-to-fix-failed"                | false   | [TestFailed]  | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")]  | []                                                                       | []
    "test-attempt-to-fix-succeeded"             | true    | [TestSucceed] | [new TestFQN("org.example.TestSucceed", "Example.add adds two numbers")] | []                                                                       | []
    "test-attempt-to-fix-quarantined-failed"    | false   | [TestFailed]  | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")]  | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")]  | []
    "test-attempt-to-fix-quarantined-succeeded" | true    | [TestSucceed] | [new TestFQN("org.example.TestSucceed", "Example.add adds two numbers")] | [new TestFQN("org.example.TestSucceed", "Example.add adds two numbers")] | []
    "test-attempt-to-fix-disabled-failed"       | false   | [TestFailed]  | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")]  | []                                                                       | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")]
    "test-attempt-to-fix-disabled-succeeded"    | true    | [TestSucceed] | [new TestFQN("org.example.TestSucceed", "Example.add adds two numbers")] | []                                                                       | [new TestFQN("org.example.TestSucceed", "Example.add adds two numbers")]
  }

  def "test capabilities tagging #testcaseName"() {
    setup:
    runTests([TestSucceed], true)

    expect:
    assertCapabilities(ScalatestUtils.CAPABILITIES, 4)
  }

  @Override
  String instrumentedLibraryName() {
    return "scalatest"
  }

  @Override
  String instrumentedLibraryVersion() {
    return ScalatestUtils.scalatestVersion
  }

  void runTests(List<Class<?>> tests, boolean expectSuccess = true) {
    def runnerArguments = ["-o"] // standard out reporting
    for (Class<?> test : tests) {
      runnerArguments += ["-s", test.name]
    }

    def result = Runner.run((String[]) runnerArguments.toArray(new String[0]))
    if (result != expectSuccess) {
      throw new AssertionError("Expected $expectSuccess execution status, got $result")
    }
  }

  void runTestsWithSbtFramework(List<Class<?>> tests, boolean expectSuccess = true) {
    def framework = new Framework()
    def fingerprint = framework.fingerprints().find { it instanceof SubclassFingerprint }
    def taskDefs = tests.collect {
      new TaskDef(it.name, fingerprint, false, [new SuiteSelector()] as Selector[])
    } as TaskDef[]

    def runner = framework.runner([] as String[], [] as String[], getClass().classLoader)
    def events = []
    EventHandler eventHandler = { Event event -> events << event }
    Deque<Task> tasks = new ArrayDeque<>(Arrays.asList(runner.tasks(taskDefs)))
    while (!tasks.isEmpty()) {
      tasks.addAll(tasks.poll().execute(eventHandler, [] as Logger[]))
    }
    runner.done()

    def result = events.every { it.status() != Status.Failure && it.status() != Status.Error }
    if (result != expectSuccess) {
      throw new AssertionError("Expected $expectSuccess execution status, got $result")
    }
  }
}
