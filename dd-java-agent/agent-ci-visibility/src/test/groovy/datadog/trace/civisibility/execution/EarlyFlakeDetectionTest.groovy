package datadog.trace.civisibility.execution

import datadog.trace.api.civisibility.execution.ExecutionAggregation
import datadog.trace.api.civisibility.execution.TestStatus
import datadog.trace.api.civisibility.telemetry.tag.RetryReason
import datadog.trace.civisibility.config.ExecutionsByDuration
import spock.lang.Specification

class EarlyFlakeDetectionTest extends Specification {

  def "test EFD exits on flake"() {
    setup:
    def executionPolicy = new EarlyFlakeDetection([new ExecutionsByDuration(Long.MAX_VALUE, 3)], false)

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome.retryReason() == null
    !outcome.lastExecution()
    !outcome.failureSuppressed()
    outcome.aggregation() == ExecutionAggregation.ONLY_FAILED
    outcome.finalStatus() == null

    when:
    def outcome2 = executionPolicy.registerExecution(TestStatus.pass, 0)

    then:
    outcome2.retryReason() == RetryReason.efd
    outcome2.lastExecution()
    !outcome2.failureSuppressed()
    outcome2.aggregation() == ExecutionAggregation.MIXED
    outcome2.finalStatus() == TestStatus.fail
  }

  def "test EFD failed all executions"() {
    setup:
    def executionPolicy = new EarlyFlakeDetection([new ExecutionsByDuration(Long.MAX_VALUE, 3)], false)

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome.retryReason() == null
    !outcome.lastExecution()
    !outcome.failureSuppressed()
    outcome.aggregation() == ExecutionAggregation.ONLY_FAILED
    outcome.finalStatus() == null

    when:
    def outcome2 = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome2.retryReason() == RetryReason.efd
    !outcome2.lastExecution()
    !outcome2.failureSuppressed()
    outcome2.aggregation() == ExecutionAggregation.ONLY_FAILED
    outcome2.finalStatus() == null

    when:
    def outcome3 = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome3.retryReason() == RetryReason.efd
    outcome3.lastExecution()
    !outcome3.failureSuppressed()
    outcome3.aggregation() == ExecutionAggregation.ONLY_FAILED
    outcome3.finalStatus() == TestStatus.fail
  }

  def "test EFD succeeded all executions"() {
    setup:
    def executionPolicy = new EarlyFlakeDetection([new ExecutionsByDuration(Long.MAX_VALUE, 3)], false)

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
    outcome2.retryReason() == RetryReason.efd
    !outcome2.lastExecution()
    !outcome2.failureSuppressed()
    outcome2.aggregation() == ExecutionAggregation.ONLY_PASSED
    outcome2.finalStatus() == null

    when:
    def outcome3 = executionPolicy.registerExecution(TestStatus.pass, 0)

    then:
    outcome3.retryReason() == RetryReason.efd
    outcome3.lastExecution()
    !outcome3.failureSuppressed()
    outcome3.aggregation() == ExecutionAggregation.ONLY_PASSED
    outcome3.finalStatus() == TestStatus.pass
  }

  def "test EFD adaptive retry count"() {
    when:
    def executionPolicy = new EarlyFlakeDetection([new ExecutionsByDuration(100, 3), new ExecutionsByDuration(Long.MAX_VALUE, 1)], false)

    then:
    !executionPolicy.registerExecution(TestStatus.fail, 0).lastExecution()
    !executionPolicy.registerExecution(TestStatus.fail, 0).lastExecution()
    executionPolicy.registerExecution(TestStatus.fail, 0).lastExecution()

    when:
    executionPolicy = new EarlyFlakeDetection([new ExecutionsByDuration(100, 3), new ExecutionsByDuration(Long.MAX_VALUE, 1)], false)

    then:
    !executionPolicy.registerExecution(TestStatus.fail, 0).lastExecution()
    executionPolicy.registerExecution(TestStatus.fail, 101).lastExecution() // exceed duration, go from "3 retries" bracket to "1 retry" bracket
  }
}
