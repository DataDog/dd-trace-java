package org.example

import java.util.concurrent.atomic.AtomicInteger

import datadog.trace.api.civisibility.NoAutoRetry
import datadog.trace.api.civisibility.PreventFailureSuppression
import munit.FunSuite

object TestAutomationExcluded {
  val executions        = new AtomicInteger()
  val skippedExecutions = new AtomicInteger()
}

class TestAutomationExcluded extends FunSuite {
  private val noRetry            = new munit.Tag(NoAutoRetry.TAG)
  private val preventSuppression =
    new munit.Tag(PreventFailureSuppression.TAG)

  test("protected_failure".tag(noRetry).tag(preventSuppression)) {
    TestAutomationExcluded.executions.incrementAndGet()
    assert(false)
  }

  test("protected_skip".tag(noRetry).tag(preventSuppression).ignore) {
    TestAutomationExcluded.skippedExecutions.incrementAndGet()
    assert(false)
  }
}
