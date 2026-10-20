package org.example

import datadog.trace.api.civisibility.TestAutomationExclusions
import munit.FunSuite

class TestAutomationExcluded extends FunSuite {
  private val noRetry            = new munit.Tag(TestAutomationExclusions.NO_AUTO_RETRY)
  private val preventSuppression =
    new munit.Tag(TestAutomationExclusions.PREVENT_FAILURE_SUPPRESSION)

  test("protected_failure".tag(noRetry).tag(preventSuppression)) {
    assert(false)
  }

  test("protected_skip".tag(noRetry).tag(preventSuppression).ignore) {
    assert(false)
  }
}
