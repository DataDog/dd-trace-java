package org.example

import datadog.trace.api.civisibility.NoAutoRetry
import datadog.trace.api.civisibility.PreventFailureSuppression
import java.util.concurrent.atomic.AtomicIntegerArray
import spock.lang.Specification

class TestAutomationExcludedParameterizedAnnotations extends Specification {
  static final AtomicIntegerArray EXECUTIONS = new AtomicIntegerArray(2)

  @NoAutoRetry
  @PreventFailureSuppression
  def "protected_failure #row"() {
    expect:
    EXECUTIONS.incrementAndGet(row) < 0

    where:
    row << [0, 1]
  }
}
