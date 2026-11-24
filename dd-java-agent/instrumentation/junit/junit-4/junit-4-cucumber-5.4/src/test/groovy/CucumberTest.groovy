import datadog.trace.api.DisableTestTrace
import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.api.civisibility.telemetry.tag.TestFrameworkInstrumentation
import datadog.trace.civisibility.CiVisibilityInstrumentationTest
import datadog.trace.instrumentation.junit4.CucumberTracingListener
import datadog.trace.instrumentation.junit4.CucumberUtils
import datadog.trace.instrumentation.junit4.TestEventsHandlerHolder
import io.cucumber.core.options.Constants
import org.example.cucumber.TestSucceedCucumber
import org.junit.runner.JUnitCore

import java.util.stream.Collectors

@DisableTestTrace(reason = "avoid self-tracing")
class CucumberTest extends CiVisibilityInstrumentationTest {

  def runner = new JUnitCore()

  def "test automation exclusions keep a protected scenario blocking and execute once"() {
    given:
    def path = "org/example/cucumber/calculator/automation_excluded.feature"
    def id = new TestFQN("classpath:${path}:Basic Arithmetic", "Addition")
    givenDisabledTests([id])
    givenQuarantinedTests([id])
    givenAttemptToFixTests([id])
    givenFlakyRetryEnabled(true)
    givenFlakyTests([id])
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests([])

    when:
    runFeatures([path], false)

    then:
    def testSpans = TEST_WRITER.toList().flatten().findAll { it.getTag("test.name") == "Addition" }
    testSpans.size() == 1
    def testSpan = testSpans.first()
    testSpan.getTag("test.test_management.no_auto_retry")
    testSpan.getTag("test.test_management.prevent_failure_suppression")
    testSpan.getTag("test.test_management.is_test_disabled")
    testSpan.getTag("test.test_management.is_quarantined")
    testSpan.getTag("test.test_management.is_attempt_to_fix")
    testSpan.getTag("test.test_management.attempt_to_fix_passed") == null
  }

  def "test #testcaseName"() {
    runFeatures(features, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                          | success | features
    "test-succeed"                        | true    | ["org/example/cucumber/calculator/basic_arithmetic.feature"]
    "test-scenario-outline-${version()}"  | true    | ["org/example/cucumber/calculator/basic_arithmetic_with_examples.feature"]
    "test-failure"                        | false   | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"]
    "test-multiple-features-${version()}" | false   | [
      "org/example/cucumber/calculator/basic_arithmetic.feature",
      "org/example/cucumber/calculator/basic_arithmetic_failed.feature"
    ]
    "test-name-with-brackets"             | true    | ["org/example/cucumber/calculator/name_with_brackets.feature"]
    "test-empty-name-${version()}"        | true    | ["org/example/cucumber/calculator/empty_scenario_name.feature"]
  }

  def "test ITR #testcaseName"() {
    givenSkippableTests(skippedTests)

    runFeatures(features)

    assertSpansData(testcaseName)

    where:
    testcaseName                 | features                                                                       | skippedTests
    "test-itr-skipping"          | ["org/example/cucumber/calculator/basic_arithmetic.feature"]                   | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic.feature:Basic Arithmetic", "Addition", null)
    ]
    "test-itr-unskippable"       | ["org/example/cucumber/calculator/basic_arithmetic_unskippable.feature"]       | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic_unskippable.feature:Basic Arithmetic", "Addition", null)
    ]
    "test-itr-unskippable-suite" | ["org/example/cucumber/calculator/basic_arithmetic_unskippable_suite.feature"] | [
      new TestIdentifier("classpath:org/example/cucumber/calculator/basic_arithmetic_unskippable_suite.feature:Basic Arithmetic", "Addition", null)
    ]
  }

  def "test flaky retries #testcaseName"() {
    givenFlakyRetryEnabled(true)
    givenFlakyTests(retriedTests)

    runFeatures(features, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                               | success | features                                                                          | retriedTests
    "test-failure"                             | false   | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"]               | []
    "test-retry-failure"                       | false   | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"]               | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]
    "test-retry-scenario-outline-${version()}" | false   | ["org/example/cucumber/calculator/basic_arithmetic_with_examples_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_with_examples_failed.feature:Basic Arithmetic With Examples", "Many additions")
    ]
  }

  def "test early flakiness detection #testcaseName"() {
    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(knownTestsList)

    runFeatures(features)

    assertSpansData(testcaseName)

    where:
    testcaseName                                 | features                                                                   | knownTestsList
    "test-efd-known-test"                        | ["org/example/cucumber/calculator/basic_arithmetic.feature"]               | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic.feature:Basic Arithmetic", "Addition")
    ]
    "test-efd-new-test"                          | ["org/example/cucumber/calculator/basic_arithmetic.feature"]               | []
    "test-efd-new-scenario-outline-${version()}" | ["org/example/cucumber/calculator/basic_arithmetic_with_examples.feature"] | []
    "test-efd-new-slow-test"                     | ["org/example/cucumber/calculator/basic_arithmetic_slow.feature"]          | []
    "test-efd-skip-new-test"                     | ["org/example/cucumber/calculator/basic_arithmetic_skip_efd.feature"]      | []
  }

  def "test quarantined #testcaseName"() {
    givenQuarantinedTests(quarantined)

    runFeatures(features, true)

    assertSpansData(testcaseName)

    where:
    testcaseName              | features                                                            | quarantined
    "test-quarantined-failed" | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]
  }

  def "test quarantined auto-retries #testcaseName"() {
    givenQuarantinedTests(quarantined)

    givenFlakyRetryEnabled(true)
    givenFlakyTests(retried)

    // every test retry fails, but the build status is successful
    runFeatures(features, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                  | features                                                            | quarantined       | retried
    "test-quarantined-failed-atr" | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                       | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]
  }

  def "test quarantined early flakiness detection #testcaseName"() {
    givenQuarantinedTests(quarantined)

    givenEarlyFlakinessDetectionEnabled(true)
    givenKnownTests(known)

    // every retry fails, but the build status is successful
    runFeatures(features, true)

    assertSpansData(testcaseName)

    where:
    testcaseName                    | features                                                            | quarantined     | known
    "test-quarantined-failed-known" | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                       | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]
    "test-quarantined-failed-efd"   | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                       | []
  }

  def "test disabled #testcaseName"() {
    givenDisabledTests(disabled)

    runFeatures(features, true)

    assertSpansData(testcaseName)

    where:
    testcaseName           | features                                                            | disabled
    "test-disabled-failed" | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]
  }

  def "test attempt to fix #testcaseName"() {
    givenQuarantinedTests(quarantined)
    givenDisabledTests(disabled)
    givenAttemptToFixTests(attemptToFix)

    runFeatures(features, success)

    assertSpansData(testcaseName)

    where:
    testcaseName                                | success | features                                                            | attemptToFix | quarantined                                                                                                             | disabled
    "test-attempt-to-fix-failed"                | false   | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                                          | []                                                                                                                      | []
    "test-attempt-to-fix-succeeded"             | true    | ["org/example/cucumber/calculator/basic_arithmetic.feature"]        | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                                          | []                                                                                                                      | []
    "test-attempt-to-fix-quarantined-failed"    | false   | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                                          | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                                                                                                                                                                    | []
    "test-attempt-to-fix-quarantined-succeeded" | true    | ["org/example/cucumber/calculator/basic_arithmetic.feature"]        | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                                          | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                                                                                                                                                                    | []
    "test-attempt-to-fix-disabled-failed"       | false   | ["org/example/cucumber/calculator/basic_arithmetic_failed.feature"] | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                                          | []                                                                                                                      | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic_failed.feature:Basic Arithmetic", "Addition")
    ]
    "test-attempt-to-fix-disabled-succeeded"    | true    | ["org/example/cucumber/calculator/basic_arithmetic.feature"]        | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic.feature:Basic Arithmetic", "Addition")
    ]                                                                                                                                          | []                                                                                                                      | [
      new TestFQN("classpath:org/example/cucumber/calculator/basic_arithmetic.feature:Basic Arithmetic", "Addition")
    ]
  }

  def "test capabilities tagging #testcaseName"() {
    setup:
    runFeatures(["org/example/cucumber/calculator/basic_arithmetic.feature"], true)

    expect:
    assertCapabilities(CucumberUtils.CAPABILITIES, 4)
  }

  private String version() {
    return CucumberTracingListener.FRAMEWORK_VERSION < "7" ? CucumberTracingListener.FRAMEWORK_VERSION : "latest"
  }

  private void runFeatures(List<String> classpathFeatures, boolean expectSuccess = true) {
    System.setProperty(Constants.GLUE_PROPERTY_NAME, "org.example.cucumber.calculator")
    System.setProperty(Constants.FILTER_TAGS_PROPERTY_NAME, "not @Disabled")
    System.setProperty(Constants.FEATURES_PROPERTY_NAME, classpathFeatures.stream()
    .map(f -> "classpath:" + f).
    collect(Collectors.joining(",")))

    TestEventsHandlerHolder.start(TestFrameworkInstrumentation.CUCUMBER, CucumberUtils.CAPABILITIES)
    try {
      def result = runner.run(TestSucceedCucumber)
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
      TestEventsHandlerHolder.stop(TestFrameworkInstrumentation.CUCUMBER)
    }
  }

  @Override
  String instrumentedLibraryName() {
    CucumberTracingListener.FRAMEWORK_NAME
  }

  @Override
  String instrumentedLibraryVersion() {
    CucumberTracingListener.FRAMEWORK_VERSION
  }
}
