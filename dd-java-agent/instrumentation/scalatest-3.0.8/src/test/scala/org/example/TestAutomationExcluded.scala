package org.example

import datadog.trace.api.civisibility.TestAutomationExclusions
import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuite

class TestAutomationExcluded extends AnyFunSuite {
  private val noRetry            = new Tag(TestAutomationExclusions.NO_AUTO_RETRY)
  private val preventSuppression = new Tag(TestAutomationExclusions.PREVENT_FAILURE_SUPPRESSION)

  test("protected_failure", noRetry, preventSuppression) {
    assert(false)
  }

  ignore("protected_skip", noRetry, preventSuppression) {
    assert(false)
  }
}
