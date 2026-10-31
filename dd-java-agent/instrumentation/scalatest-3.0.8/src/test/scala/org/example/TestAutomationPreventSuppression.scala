package org.example

import java.util.concurrent.atomic.AtomicInteger

import datadog.trace.api.civisibility.PreventFailureSuppression
import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuite

object TestAutomationPreventSuppression {
  val executions = new AtomicInteger()
}

class TestAutomationPreventSuppression extends AnyFunSuite {
  test("blocking_failure", new Tag(PreventFailureSuppression.TAG)) {
    TestAutomationPreventSuppression.executions.incrementAndGet()
    assert(false)
  }
}
