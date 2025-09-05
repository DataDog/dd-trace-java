package datadog.trace.civisibility.test

import datadog.trace.api.Config
import datadog.trace.api.civisibility.config.TestFQN
import datadog.trace.api.civisibility.config.TestIdentifier
import datadog.trace.api.civisibility.config.TestMetadata
import datadog.trace.api.civisibility.config.TestSourceData
import datadog.trace.api.civisibility.execution.TestStatus
import datadog.trace.api.civisibility.telemetry.tag.RetryReason
import datadog.trace.api.civisibility.telemetry.tag.SkipReason
import datadog.trace.civisibility.config.EarlyFlakeDetectionSettings
import datadog.trace.civisibility.config.ExecutionSettings
import datadog.trace.civisibility.config.ExecutionsByDuration
import datadog.trace.civisibility.config.TestManagementSettings
import datadog.trace.civisibility.execution.RunNTimes
import datadog.trace.civisibility.source.LinesResolver
import datadog.trace.civisibility.source.SourcePathResolver
import spock.lang.Specification

class ExecutionStrategyTest extends Specification {

  def "test disabled + itr"() {
    setup:
    def testFQN = new TestFQN("suite", "name")
    def testID = new TestIdentifier(testFQN, null)

    def testManagementSettings = Stub(TestManagementSettings)
    testManagementSettings.isEnabled() >> true

    def executionSettings = Stub(ExecutionSettings)
    executionSettings.getTestManagementSettings() >> testManagementSettings
    executionSettings.isDisabled(testFQN) >> true
    executionSettings.isTestSkippingEnabled() >> true
    executionSettings.getSkippableTests() >> [testID: new TestMetadata(false)]

    def strategy = givenAnExecutionStrategy(executionSettings)

    expect:
    strategy.skipReason(testID) == SkipReason.DISABLED
  }

  def "test attempt to fix + itr"() {
    setup:
    def testFQN = new TestFQN("suite", "name")
    def testID = new TestIdentifier(testFQN, null)

    def testManagementSettings = Stub(TestManagementSettings)
    testManagementSettings.isEnabled() >> true

    def executionSettings = Stub(ExecutionSettings)
    executionSettings.getTestManagementSettings() >> testManagementSettings
    executionSettings.isAttemptToFix(testFQN) >> true
    executionSettings.isTestSkippingEnabled() >> true
    executionSettings.getSkippableTests() >> [testID: new TestMetadata(false)]

    def strategy = givenAnExecutionStrategy(executionSettings)

    expect:
    strategy.skipReason(testID) == null
  }

  def "test attempt to fix + atr"() {
    setup:
    def testFQN = new TestFQN("suite", "name")
    def testID = new TestIdentifier(testFQN, null)

    def testManagementSettings = Stub(TestManagementSettings)
    testManagementSettings.isEnabled() >> true

    def executionSettings = Stub(ExecutionSettings)
    executionSettings.getTestManagementSettings() >> testManagementSettings
    executionSettings.isAttemptToFix(testFQN) >> true
    executionSettings.isFlakyTestRetriesEnabled() >> true
    executionSettings.isFlaky(testFQN) >> true

    def strategy = givenAnExecutionStrategy(executionSettings)

    expect:
    strategy.executionPolicy(testID, TestSourceData.UNKNOWN, []).class == RunNTimes
  }

  def "test attempt to fix + efd"() {
    setup:
    def testFQN = new TestFQN("suite", "name")
    def testID = new TestIdentifier(testFQN, null)

    def testManagementSettings = Stub(TestManagementSettings)
    testManagementSettings.isEnabled() >> true
    testManagementSettings.getAttemptToFixExecutions() >> Collections.singletonList(new ExecutionsByDuration(Long.MAX_VALUE, 20))

    def earlyFlakeDetectionSettings = Stub(EarlyFlakeDetectionSettings)
    earlyFlakeDetectionSettings.isEnabled() >> true

    def executionSettings = Stub(ExecutionSettings)
    executionSettings.getTestManagementSettings() >> testManagementSettings
    executionSettings.getEarlyFlakeDetectionSettings() >> earlyFlakeDetectionSettings
    executionSettings.isAttemptToFix(testFQN) >> true
    executionSettings.isKnownTestsDataAvailable() >> true
    executionSettings.isKnown(testFQN) >> false

    when:
    def strategy = givenAnExecutionStrategy(executionSettings)
    def policy = strategy.executionPolicy(testID, TestSourceData.UNKNOWN, [])

    then:
    policy.class == RunNTimes

    when:
    def outcome = policy.registerExecution(TestStatus.pass, 0)

    then:
    outcome.retryReason() == null // first execution is not a retry

    when:
    def secondOutcome = policy.registerExecution(TestStatus.pass, 0)

    then:
    secondOutcome.retryReason() == RetryReason.attemptToFix
  }

  private ExecutionStrategy givenAnExecutionStrategy(ExecutionSettings executionSettings = ExecutionSettings.EMPTY) {
    def config = Config.get()
    def resolver = Stub(SourcePathResolver)
    def linesResolver = Stub(LinesResolver)

    return new ExecutionStrategy(
      config,
      executionSettings,
      resolver,
      linesResolver
      )
  }
}
