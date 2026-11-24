package org.example

import java.util.concurrent.atomic.AtomicInteger

import datadog.trace.api.civisibility.NoAutoRetry
import munit.FunSuite

object TestAutomationRetryEligibility {
  val excludedExecutions = new AtomicInteger()
  val controlExecutions  = new AtomicInteger()
}

class TestAutomationRetryEligibility extends FunSuite {
  test("excluded_pass".tag(new munit.Tag(NoAutoRetry.TAG))) {
    TestAutomationRetryEligibility.excludedExecutions.incrementAndGet()
  }

  test("control_pass") {
    TestAutomationRetryEligibility.controlExecutions.incrementAndGet()
  }
}
