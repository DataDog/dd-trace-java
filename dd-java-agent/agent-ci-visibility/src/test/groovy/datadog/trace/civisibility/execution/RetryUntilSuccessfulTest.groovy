package datadog.trace.civisibility.execution

import datadog.trace.api.civisibility.execution.TestStatus
import datadog.trace.api.civisibility.telemetry.tag.RetryReason
import spock.lang.Specification

import java.util.concurrent.atomic.AtomicInteger

class RetryUntilSuccessfulTest extends Specification {

  def "test retry until successful"() {
    setup:
    def executionPolicy = new RetryUntilSuccessful(3, false, new AtomicInteger())

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome.retryReason() == null
    !outcome.lastExecution()
    outcome.failureSuppressed()
    !outcome.failedAllRetries()
    !outcome.succeededAllRetries()

    when:
    def outcome2 = executionPolicy.registerExecution(TestStatus.pass, 0)

    then:
    outcome2.retryReason() == RetryReason.atr
    outcome2.lastExecution()
    !outcome2.failureSuppressed()
    !outcome2.failedAllRetries()
    !outcome2.succeededAllRetries()
  }

  def "test fail all retries"() {
    setup:
    def executionPolicy = new RetryUntilSuccessful(3, false, new AtomicInteger())

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome.retryReason() == null
    !outcome.lastExecution()
    outcome.failureSuppressed()
    !outcome.failedAllRetries()
    !outcome.succeededAllRetries()

    when:
    def outcome2 = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome2.retryReason() == RetryReason.atr
    !outcome2.lastExecution()
    outcome2.failureSuppressed()
    !outcome2.failedAllRetries()
    !outcome2.succeededAllRetries()

    when:
    def outcome3 = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome3.retryReason() == RetryReason.atr
    outcome3.lastExecution()
    !outcome3.failureSuppressed()
    outcome3.failedAllRetries()
    !outcome2.succeededAllRetries()
  }

  def "test succeed on last try"() {
    setup:
    def executionPolicy = new RetryUntilSuccessful(3, false, new AtomicInteger())

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome.retryReason() == null
    !outcome.lastExecution()
    outcome.failureSuppressed()
    !outcome.failedAllRetries()
    !outcome.succeededAllRetries()

    when:
    def outcome2 = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome2.retryReason() == RetryReason.atr
    !outcome2.lastExecution()
    outcome2.failureSuppressed()
    !outcome2.failedAllRetries()
    !outcome2.succeededAllRetries()

    when:
    def outcome3 = executionPolicy.registerExecution(TestStatus.pass, 0)

    then:
    outcome3.retryReason() == RetryReason.atr
    outcome3.lastExecution()
    !outcome3.failureSuppressed()
    !outcome3.failedAllRetries()
    !outcome2.succeededAllRetries()
  }

  def "test succeed on first try"() {
    setup:
    def executionPolicy = new RetryUntilSuccessful(3, false, new AtomicInteger())

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.pass, 0)

    then:
    outcome.retryReason() == null
    outcome.lastExecution()
    !outcome.failureSuppressed()
    !outcome.failedAllRetries()
    !outcome.succeededAllRetries()
  }

  def "test suppress failures"() {
    setup:
    def executionPolicy = new RetryUntilSuccessful(3, true, new AtomicInteger())

    when:
    def outcome = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome.retryReason() == null
    !outcome.lastExecution()
    outcome.failureSuppressed()
    !outcome.failedAllRetries()
    !outcome.succeededAllRetries()

    when:
    def outcome2 = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome2.retryReason() == RetryReason.atr
    !outcome2.lastExecution()
    outcome2.failureSuppressed()
    !outcome2.failedAllRetries()
    !outcome2.succeededAllRetries()

    when:
    def outcome3 = executionPolicy.registerExecution(TestStatus.fail, 0)

    then:
    outcome3.retryReason() == RetryReason.atr
    outcome3.lastExecution()
    outcome3.failureSuppressed()
    outcome3.failedAllRetries()
    !outcome2.succeededAllRetries()
  }
}
