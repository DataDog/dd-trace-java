import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.diff.LineDiff
import datadog.trace.instrumentation.scalatest.ScalatestUtils
import org.example.TestFailed
import org.example.TestFailedParameterized
import org.example.TestFailedSuite
import org.example.TestFailedThenSucceed
import org.example.TestIgnored
import org.example.TestIgnoredCanceled
import org.example.TestIgnoredPending
import org.example.TestSucceed
import org.example.TestSucceedFlatSpec
import org.example.TestSucceedMoreCases
import org.example.TestSucceedParameterized
import org.example.TestSucceedSkipEfd
import org.example.TestSucceedSlow
import org.example.TestSucceedUnskippable
import org.scalatest.tools.Runner

class ScalatestTest extends CiVisibilityInstrumentationTest {

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
    "test-attempt-to-fix-quarantined-failed"    | true    | [TestFailed]  | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")]  | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")]  | []
    "test-attempt-to-fix-quarantined-succeeded" | true    | [TestSucceed] | [new TestFQN("org.example.TestSucceed", "Example.add adds two numbers")] | [new TestFQN("org.example.TestSucceed", "Example.add adds two numbers")] | []
    "test-attempt-to-fix-disabled-failed"       | true    | [TestFailed]  | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")]  | []                                                                       | [new TestFQN("org.example.TestFailed", "Example.add adds two numbers")]
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
}
