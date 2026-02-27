package datadog.trace.civisibility.execution

import datadog.trace.api.civisibility.execution.ExecutionAggregation
import datadog.trace.api.civisibility.execution.TestStatus
import spock.lang.Specification

class QuarantineTest extends Specification {

  def "test quarantine passed"() {
    setup:
    def executionPolicy = new Quarantine()

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.pass, 0)

    then:
    outcome.retryReason() == null
    outcome.lastExecution()
    !outcome.failureSuppressed()
    outcome.aggregation() == ExecutionAggregation.ONLY_PASSED
    outcome.finalStatus() == TestStatus.pass
  }

  def "test quarantine failed"() {
    setup:
    def executionPolicy = new Quarantine()

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
