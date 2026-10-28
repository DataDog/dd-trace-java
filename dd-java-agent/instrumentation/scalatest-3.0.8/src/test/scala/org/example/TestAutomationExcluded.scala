package org.example

import datadog.trace.api.civisibility.NoAutoRetry
import datadog.trace.api.civisibility.PreventFailureSuppression
import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuite

class TestAutomationExcluded extends AnyFunSuite {
  private val noRetry            = new Tag(NoAutoRetry.TAG)
  private val preventSuppression = new Tag(PreventFailureSuppression.TAG)

  test("protected_failure", noRetry, preventSuppression) {
    assert(false)
  }

  ignore("protected_skip", noRetry, preventSuppression) {
    assert(false)
  }
}
