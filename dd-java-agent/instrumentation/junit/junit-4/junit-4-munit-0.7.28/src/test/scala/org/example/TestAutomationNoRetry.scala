package org.example

import java.util.concurrent.atomic.AtomicInteger

import datadog.trace.api.civisibility.NoAutoRetry
import munit.FunSuite

object TestAutomationNoRetry {
  val executions = new AtomicInteger()
}

class TestAutomationNoRetry extends FunSuite {
  test("no_retry_failure".tag(new munit.Tag(NoAutoRetry.TAG))) {
    TestAutomationNoRetry.executions.incrementAndGet()
    assert(false)
  }
}
