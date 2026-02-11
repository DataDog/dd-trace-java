package datadog.trace.civisibility.execution

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
    !outcome.failedAllRetries()
    !outcome.succeededAllRetries()
    outcome.finalStatus() == status

    where:
    status << [TestStatus.pass, TestStatus.fail, TestStatus.skip]
  }
}
