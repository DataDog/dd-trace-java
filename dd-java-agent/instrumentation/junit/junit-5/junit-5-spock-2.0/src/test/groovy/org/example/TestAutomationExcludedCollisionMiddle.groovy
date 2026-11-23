package org.example

import datadog.trace.api.civisibility.NoAutoRetry
import datadog.trace.api.civisibility.PreventFailureSuppression

class TestAutomationExcludedCollisionMiddle extends TestAutomationExcludedCollisionBase {
  static int protectedExecutions

  @NoAutoRetry
  @PreventFailureSuppression
  def "same_named_feature"() {
    expect:
    ++protectedExecutions < 0
  }
}
