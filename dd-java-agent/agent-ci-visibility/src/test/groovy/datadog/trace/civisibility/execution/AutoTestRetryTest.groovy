package datadog.trace.civisibility.execution

import datadog.trace.api.civisibility.execution.ExecutionAggregation
import datadog.trace.api.civisibility.execution.TestStatus
import datadog.trace.api.civisibility.telemetry.tag.RetryReason
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicInteger

class AutoTestRetryTest extends Specification {

  def "test retry until successful"() {
    setup:
    def executionPolicy = new AutoTestRetry(3, false, new AtomicInteger())

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome.retryReason() == null
    !outcome.lastExecution()
    outcome.failureSuppressed()
    outcome.aggregation() == ExecutionAggregation.ONLY_FAILED
    outcome.finalStatus() == null

    when:
    def outcome2 = executionPolicy.registerExecution(TestStatus.pass, 0)

    then:
    outcome2.retryReason() == RetryReason.atr
    outcome2.lastExecution()
    !outcome2.failureSuppressed()
    outcome2.aggregation() == ExecutionAggregation.MIXED
    outcome2.finalStatus() == TestStatus.pass
  }

  def "test fail all retries"() {
    setup:
    def executionPolicy = new AutoTestRetry(3, false, new AtomicInteger())

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome.retryReason() == null
    !outcome.lastExecution()
    outcome.failureSuppressed()
    outcome.aggregation() == ExecutionAggregation.ONLY_FAILED
    outcome.finalStatus() == null

    when:
    def outcome2 = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome2.retryReason() == RetryReason.atr
    !outcome2.lastExecution()
    outcome2.failureSuppressed()
    outcome2.aggregation() == ExecutionAggregation.ONLY_FAILED
    outcome2.finalStatus() == null

    when:
    def outcome3 = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome3.retryReason() == RetryReason.atr
    outcome3.lastExecution()
    !outcome3.failureSuppressed()
    outcome3.aggregation() == ExecutionAggregation.ONLY_FAILED
    outcome3.finalStatus() == TestStatus.fail
  }

  def "test succeed on first try"() {
    setup:
    def executionPolicy = new AutoTestRetry(3, false, new AtomicInteger())

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.pass, 0)

    then:
    outcome.retryReason() == null
    outcome.lastExecution()
    !outcome.failureSuppressed()
    outcome.aggregation() == ExecutionAggregation.ONLY_PASSED
    outcome.finalStatus() == TestStatus.pass
  }

  def "test suppress failures"() {
    setup:
    def executionPolicy = new AutoTestRetry(3, true, new AtomicInteger())

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome.retryReason() == null
    !outcome.lastExecution()
    outcome.failureSuppressed()
    outcome.aggregation() == ExecutionAggregation.ONLY_FAILED
    outcome.finalStatus() == null

    when:
    def outcome2 = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome2.retryReason() == RetryReason.atr
    !outcome2.lastExecution()
    outcome2.failureSuppressed()
    outcome2.aggregation() == ExecutionAggregation.ONLY_FAILED
    outcome2.finalStatus() == null

    when:
    def outcome3 = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome3.retryReason() == RetryReason.atr
    outcome3.lastExecution()
    outcome3.failureSuppressed()
    outcome3.aggregation() == ExecutionAggregation.ONLY_FAILED
    outcome3.finalStatus() == TestStatus.pass
  }
}
