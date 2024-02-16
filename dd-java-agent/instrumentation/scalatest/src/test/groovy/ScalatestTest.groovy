import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
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
import org.example.TestSucceedParameterized
import org.example.TestSucceedUnskippable
import org.scalatest.tools.Runner

class ScalatestTest extends CiVisibilityInstrumentationTest {

  def "test #testcaseName"() {
    runTests(tests)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                 | tests                      | expectedTracesCount
    "test-succeed"               | [TestSucceed]              | 2
    "test-succeed-flat-spec"     | [TestSucceedFlatSpec]      | 2
    "test-succeed-parameterized" | [TestSucceedParameterized] | 2
    "test-failed"                | [TestFailed]               | 2
    "test-ignored"               | [TestIgnored]              | 2
    "test-canceled"              | [TestIgnoredCanceled]      | 2
    "test-pending"               | [TestIgnoredPending]       | 2
    "test-failed-suite"          | [TestFailedSuite]          | 1
  }

  def "test ITR #testcaseName"() {
    givenSkippableTests(skippedTests)
    runTests(tests)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName                       | tests                    | expectedTracesCount | skippedTests
    "test-itr-skipping"                | [TestSucceed]            | 2                   | [new TestIdentifier("org.example.TestSucceed", "Example.add adds two numbers", null, null)]
    "test-itr-unskippable"             | [TestSucceedUnskippable] | 2                   | [
      new TestIdentifier("org.example.TestSucceedUnskippable", "test should assert something", null, null)
    ]
    "test-itr-unskippable-not-skipped" | [TestSucceedUnskippable] | 2                   | []
  }

  def "test flaky retries #testcaseName"() {
    givenFlakyTests(retriedTests)

    runTests(tests)

    assertSpansData(testcaseName, expectedTracesCount)

    where:
    testcaseName               | tests                     | expectedTracesCount | retriedTests
    "test-failed"              | [TestFailed]              | 2                   | []
    "test-retry-failed"        | [TestFailed]              | 6                   | [new TestIdentifier("org.example.TestFailed", "Example.add adds two numbers", null, null)]
    "test-retry-parameterized" | [TestFailedParameterized] | 2                   | [
      new TestIdentifier("org.example.TestFailedParameterized", "addition should correctly add two numbers", null, null)
    ]
    "test-failed-then-succeed" | [TestFailedThenSucceed]   | 4                   | [new TestIdentifier("org.example.TestFailedThenSucceed", "Example.add adds two numbers", null, null)]
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
