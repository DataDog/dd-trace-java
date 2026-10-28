package org.example

import datadog.trace.api.civisibility.PreventFailureSuppression
import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuite

class TestAutomationPreventSuppression extends AnyFunSuite {
  test("blocking_failure", new Tag(PreventFailureSuppression.TAG)) {
    assert(false)
  }
}
