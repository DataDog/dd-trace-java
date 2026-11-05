package org.example

import datadog.trace.api.civisibility.NoAutoRetry
import datadog.trace.api.civisibility.PreventFailureSuppression
import spock.lang.Specification

class TestAutomationExcluded extends Specification {
  static int executions

  @NoAutoRetry
  @PreventFailureSuppression
  def "protected_failure"() {
    expect:
    ++executions < 0
  }
}
