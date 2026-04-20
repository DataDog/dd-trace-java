package datadog.trace.civisibility.execution

import datadog.trace.api.civisibility.execution.ExecutionAggregation
import datadog.trace.api.civisibility.execution.TestStatus
import spock.lang.Specification

class RegularExecutionTest extends Specification {

  def "test regular execution outcome"() {
    setup:
    def executionPolicy = Regular.INSTANCE

    when:
    def outcome = executionPolicy.registerExecution(status, 0)

    then:
    outcome.retryReason() == null
    outcome.lastExecution()
    !outcome.failureSuppressed()
    outcome.aggregation() == expectedResults
    outcome.finalStatus() == status

    where:
    status            | expectedResults
    TestStatus.pass   | ExecutionAggregation.ONLY_PASSED
    TestStatus.fail   | ExecutionAggregation.ONLY_FAILED
    TestStatus.skip   | ExecutionAggregation.ONLY_PASSED
  }
}
