package org.example

import datadog.trace.api.civisibility.NoAutoRetry
import datadog.trace.api.civisibility.PreventFailureSuppression
import munit.FunSuite

class TestAutomationExcluded extends FunSuite {
  private val noRetry            = new munit.Tag(NoAutoRetry.TAG)
  private val preventSuppression =
    new munit.Tag(PreventFailureSuppression.TAG)

  test("protected_failure".tag(noRetry).tag(preventSuppression)) {
    assert(false)
  }

  test("protected_skip".tag(noRetry).tag(preventSuppression).ignore) {
    assert(false)
  }
}
