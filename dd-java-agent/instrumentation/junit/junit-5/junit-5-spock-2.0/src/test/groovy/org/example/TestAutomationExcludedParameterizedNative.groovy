package org.example

import datadog.trace.api.civisibility.NoAutoRetry
import datadog.trace.api.civisibility.PreventFailureSuppression
import spock.lang.Tag
import java.util.concurrent.atomic.AtomicIntegerArray
import spock.lang.Specification

class TestAutomationExcludedParameterizedNative extends Specification {
  static final AtomicIntegerArray EXECUTIONS = new AtomicIntegerArray(2)

  @Tag(NoAutoRetry.TAG)
  @Tag(PreventFailureSuppression.TAG)
  def "protected_failure #row"() {
    expect:
    EXECUTIONS.incrementAndGet(row) < 0

    where:
    row << [0, 1]
  }
}
