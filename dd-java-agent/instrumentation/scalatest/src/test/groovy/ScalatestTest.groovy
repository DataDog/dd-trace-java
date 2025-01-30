import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.civisibility.diff.FileDiff
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
import org.example.TestSucceedSlow
import org.example.TestSucceedUnskippable
import org.scalatest.tools.Runner

class ScalatestTest extends CiVisibilityInstrumentationTest {

  def "test #testcaseName"() {
    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                 | tests
    "test-succeed"               | [TestSucceed]
    "test-succeed-flat-spec"     | [TestSucceedFlatSpec]
    "test-succeed-parameterized" | [TestSucceedParameterized]
    "test-failed"                | [TestFailed]
    "test-ignored"               | [TestIgnored]
    "test-canceled"              | [TestIgnoredCanceled]
    "test-pending"               | [TestIgnoredPending]
    "test-failed-suite"          | [TestFailedSuite]
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

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName               | tests                     | retriedTests
    "test-failed"              | [TestFailed]              | []
    "test-retry-failed"        | [TestFailed]              | [new TestIdentifier("org.example.TestFailed", "Example.add adds two numbers", null)]
    "test-retry-parameterized" | [TestFailedParameterized] | [
      new TestIdentifier("org.example.TestFailedParameterized", "addition should correctly add two numbers", null)
    ]
    "test-failed-then-succeed" | [TestFailedThenSucceed]   | [new TestIdentifier("org.example.TestFailedThenSucceed", "Example.add adds two numbers", null)]
  }

  def "test early flakiness detection #testcaseName"() {
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runTests(tests)

    assertSpansData(testcaseName)

    where:
    testcaseName                        | tests                  | knownTestsList
    "test-efd-known-test"               | [TestSucceed]          | [new TestIdentifier("org.example.TestSucceed", "Example.add adds two numbers", null)]
    "test-efd-new-test"                 | [TestSucceed]          | []
    "test-efd-new-slow-test"            | [TestSucceedSlow]      | [] // is executed only twice
    "test-efd-faulty-session-threshold" | [TestSucceedMoreCases] | []
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

  @Override
  String instrumentedLibraryName() {
    return "scalatest"
  }

  @Override
  String instrumentedLibraryVersion() {
    return ScalatestUtils.scalatestVersion
  }

  void runTests(List<Class<?>> tests) {
    def runnerArguments = ["-o"] // standard out reporting
    for (Class<?> test : tests) {
      runnerArguments += ["-s", test.name]
    }

    Runner.run((String[]) runnerArguments.toArray(new String[0]))
  }

}
