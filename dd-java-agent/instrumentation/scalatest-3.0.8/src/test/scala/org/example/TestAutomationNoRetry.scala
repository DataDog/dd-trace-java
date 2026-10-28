package org.example

import datadog.trace.api.civisibility.NoAutoRetry
import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuite

class TestAutomationNoRetry extends AnyFunSuite {
  test("no_retry_failure", new Tag(NoAutoRetry.TAG)) {
    assert(false)
  }
}
