package datadog.trace.civisibility.execution

import datadog.trace.api.civisibility.execution.ExecutionAggregation
import datadog.trace.api.civisibility.execution.TestStatus
import datadog.trace.api.civisibility.telemetry.tag.RetryReason
import spock.lang.Specification

class AttemptToFixTest extends Specification {

  def "test attempt to fix exits on failure"() {
    setup:
    def executionPolicy = new AttemptToFix(3, false)

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome.retryReason() == null
    outcome.lastExecution()
    !outcome.failureSuppressed()
    outcome.aggregation() == ExecutionAggregation.ONLY_FAILED
    outcome.finalStatus() == TestStatus.fail
  }

  def "test attempt to fix succeeded all executions"() {
    setup:
    def executionPolicy = new AttemptToFix(3, false)

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.pass, 0)

    then:
    outcome.retryReason() == null
    !outcome.lastExecution()
    !outcome.failureSuppressed()
    outcome.aggregation() == ExecutionAggregation.ONLY_PASSED
    outcome.finalStatus() == null

    when:
    def outcome2 = executionPolicy.registerExecution(TestStatus.pass, 0)

    then:
    outcome2.retryReason() == RetryReason.attemptToFix
    !outcome2.lastExecution()
    !outcome2.failureSuppressed()
    outcome2.aggregation() == ExecutionAggregation.ONLY_PASSED
    outcome2.finalStatus() == null

    when:
    def outcome3 = executionPolicy.registerExecution(TestStatus.pass, 0)

    then:
    outcome3.retryReason() == RetryReason.attemptToFix
    outcome3.lastExecution()
    !outcome3.failureSuppressed()
    outcome3.aggregation() == ExecutionAggregation.ONLY_PASSED
    outcome3.finalStatus() == TestStatus.pass
  }

  def "test attempt to fix suppresses failures when quarantined or disabled"() {
    setup:
    def executionPolicy = new AttemptToFix(3, true)

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome.retryReason() == null
    outcome.lastExecution()
    outcome.failureSuppressed()
    outcome.aggregation() == ExecutionAggregation.ONLY_FAILED
    outcome.finalStatus() == TestStatus.pass
  }
}
