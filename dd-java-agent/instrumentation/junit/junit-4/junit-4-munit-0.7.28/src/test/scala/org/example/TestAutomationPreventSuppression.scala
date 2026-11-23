package org.example

import java.util.concurrent.atomic.AtomicInteger

import datadog.trace.api.civisibility.PreventFailureSuppression
import munit.FunSuite

object TestAutomationPreventSuppression {
  val executions = new AtomicInteger()
}

class TestAutomationPreventSuppression extends FunSuite {
  test("blocking_failure".tag(new munit.Tag(PreventFailureSuppression.TAG))) {
    TestAutomationPreventSuppression.executions.incrementAndGet()
    assert(false)
  }
}
