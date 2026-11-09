package org.example

import java.util.concurrent.atomic.AtomicInteger

import datadog.trace.api.civisibility.NoAutoRetry
import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuite

object TestAutomationNoRetry {
  val executions = new AtomicInteger()
}

class TestAutomationNoRetry extends AnyFunSuite {
  test("no_retry_failure", new Tag(NoAutoRetry.TAG)) {
    TestAutomationNoRetry.executions.incrementAndGet()
    assert(false)
  }
}
