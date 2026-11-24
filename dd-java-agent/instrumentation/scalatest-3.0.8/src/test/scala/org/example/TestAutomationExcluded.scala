package org.example

import java.util.concurrent.atomic.AtomicInteger

import datadog.trace.api.civisibility.NoAutoRetry
import datadog.trace.api.civisibility.PreventFailureSuppression
import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuite

object TestAutomationExcluded {
  val executions        = new AtomicInteger()
  val skippedExecutions = new AtomicInteger()
}

class TestAutomationExcluded extends AnyFunSuite {
  private val noRetry            = new Tag(NoAutoRetry.TAG)
  private val preventSuppression = new Tag(PreventFailureSuppression.TAG)

  test("protected_failure", noRetry, preventSuppression) {
    TestAutomationExcluded.executions.incrementAndGet()
    assert(false)
  }

  ignore("protected_skip", noRetry, preventSuppression) {
    TestAutomationExcluded.skippedExecutions.incrementAndGet()
    assert(false)
  }
}
