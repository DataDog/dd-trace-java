package datadog.trace.civisibility.execution

import datadog.trace.api.civisibility.execution.TestStatus
import spock.lang.Specification

class RunOnceIgnoreOutcomeTest extends Specification {

  def "test run once ignore outcome"() {
    setup:
    def executionPolicy = new RunOnceIgnoreOutcome()

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.pass, 0)

    then:
    outcome.retryReason() == null
    outcome.lastExecution()
    !outcome.failureSuppressed()
    !outcome.failedAllRetries()
    !outcome.succeededAllRetries()
    outcome.finalStatus() == TestStatus.pass
  }

  def "test run once ignore outcome failed"() {
    setup:
    def executionPolicy = new RunOnceIgnoreOutcome()

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome.retryReason() == null
    outcome.lastExecution()
    outcome.failureSuppressed()
    !outcome.failedAllRetries()
    !outcome.succeededAllRetries()
    outcome.finalStatus() == TestStatus.pass
  }
}
