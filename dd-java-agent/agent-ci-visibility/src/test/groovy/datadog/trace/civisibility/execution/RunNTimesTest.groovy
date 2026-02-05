package datadog.trace.civisibility.execution

import datadog.trace.api.civisibility.execution.TestStatus
import datadog.trace.api.civisibility.telemetry.tag.RetryReason
import datadog.trace.civisibility.config.ExecutionsByDuration
import spock.lang.Specification

class RunNTimesTest extends Specification {

  def "test run N times"() {
    setup:
    def executionPolicy = new RunNTimes([new ExecutionsByDuration(Long.MAX_VALUE, 3)], false, RetryReason.efd)

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome.retryReason() == null
    !outcome.lastExecution()
    !outcome.failureSuppressed()
    !outcome.failedAllRetries()
    !outcome.succeededAllRetries()
    outcome.finalStatus() == null

    when:
    def outcome2 = executionPolicy.registerExecution(TestStatus.pass, 0)

    then:
    outcome2.retryReason() == RetryReason.efd
    !outcome2.lastExecution()
    !outcome2.failureSuppressed()
    !outcome2.failedAllRetries()
    !outcome2.succeededAllRetries()
    outcome2.finalStatus() == null

    when:
    def outcome3 = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome3.retryReason() == RetryReason.efd
    outcome3.lastExecution()
    !outcome3.failureSuppressed()
    !outcome3.failedAllRetries()
    !outcome3.succeededAllRetries()
    outcome3.finalStatus() == TestStatus.fail
  }

  def "test failed all retries"() {
    setup:
    def executionPolicy = new RunNTimes([new ExecutionsByDuration(Long.MAX_VALUE, 3)], false, RetryReason.efd)

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome.retryReason() == null
    !outcome.lastExecution()
    !outcome.failureSuppressed()
    !outcome.failedAllRetries()
    !outcome.succeededAllRetries()
    outcome.finalStatus() == null

    when:
    def outcome2 = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome2.retryReason() == RetryReason.efd
    !outcome2.lastExecution()
    !outcome2.failureSuppressed()
    !outcome2.failedAllRetries()
    !outcome2.succeededAllRetries()
    outcome2.finalStatus() == null

    when:
    def outcome3 = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome3.retryReason() == RetryReason.efd
    outcome3.lastExecution()
    !outcome3.failureSuppressed()
    outcome3.failedAllRetries()
    !outcome3.succeededAllRetries()
    outcome3.finalStatus() == TestStatus.fail
  }

  def "test succeeded all retries"() {
    setup:
    def executionPolicy = new RunNTimes([new ExecutionsByDuration(Long.MAX_VALUE, 3)], false, RetryReason.efd)

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.pass, 0)

    then:
    outcome.retryReason() == null
    !outcome.lastExecution()
    !outcome.failureSuppressed()
    !outcome.failedAllRetries()
    !outcome.succeededAllRetries()
    outcome.finalStatus() == null

    when:
    def outcome2 = executionPolicy.registerExecution(TestStatus.pass, 0)

    then:
    outcome2.retryReason() == RetryReason.efd
    !outcome2.lastExecution()
    !outcome2.failureSuppressed()
    !outcome2.failedAllRetries()
    !outcome2.succeededAllRetries()
    outcome2.finalStatus() == null

    when:
    def outcome3 = executionPolicy.registerExecution(TestStatus.pass, 0)

    then:
    outcome3.retryReason() == RetryReason.efd
    outcome3.lastExecution()
    !outcome3.failureSuppressed()
    !outcome3.failedAllRetries()
    outcome3.succeededAllRetries()
    outcome3.finalStatus() == TestStatus.pass
  }

  def "test suppress failures"() {
    setup:
    def executionPolicy = new RunNTimes([new ExecutionsByDuration(Long.MAX_VALUE, 3)], true, RetryReason.attemptToFix)

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome.retryReason() == null
    !outcome.lastExecution()
    outcome.failureSuppressed()
    !outcome.failedAllRetries()
    !outcome.succeededAllRetries()
    outcome.finalStatus() == null

    when:
    def outcome2 = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome2.retryReason() == RetryReason.attemptToFix
    !outcome2.lastExecution()
    outcome2.failureSuppressed()
    !outcome2.failedAllRetries()
    !outcome2.succeededAllRetries()
    outcome2.finalStatus() == null

    when:
    def outcome3 = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome3.retryReason() == RetryReason.attemptToFix
    outcome3.lastExecution()
    outcome3.failureSuppressed()
    outcome3.failedAllRetries()
    !outcome3.succeededAllRetries()
    outcome3.finalStatus() == TestStatus.pass
  }

  def "test adaptive retry count"() {
    when:
    def executionPolicy = new RunNTimes([new ExecutionsByDuration(100, 3), new ExecutionsByDuration(Long.MAX_VALUE, 1)], true, RetryReason.efd)

    then:
    !executionPolicy.registerExecution(TestStatus.fail, 0).lastExecution()
    !executionPolicy.registerExecution(TestStatus.fail, 0).lastExecution()
    executionPolicy.registerExecution(TestStatus.fail, 0).lastExecution()

    when:
    executionPolicy = new RunNTimes([new ExecutionsByDuration(100, 3), new ExecutionsByDuration(Long.MAX_VALUE, 1)], true, RetryReason.efd)

    then:
    !executionPolicy.registerExecution(TestStatus.fail, 0).lastExecution()
    executionPolicy.registerExecution(TestStatus.fail, 101).lastExecution() // exceed duration, go from "3 retries" bracket to "1 retry" bracket
  }
}
