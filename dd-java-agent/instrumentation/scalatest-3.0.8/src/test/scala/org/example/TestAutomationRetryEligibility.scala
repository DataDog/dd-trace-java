package org.example

import java.util.concurrent.atomic.AtomicInteger

import datadog.trace.api.civisibility.NoAutoRetry
import org.scalatest.Tag
import org.scalatest.funsuite.AnyFunSuite

object TestAutomationRetryEligibility {
  val excludedExecutions = new AtomicInteger()
  val controlExecutions  = new AtomicInteger()
}

class TestAutomationRetryEligibility extends AnyFunSuite {
  test("excluded_pass", new Tag(NoAutoRetry.TAG)) {
    TestAutomationRetryEligibility.excludedExecutions.incrementAndGet()
  }

  test("control_pass") {
    TestAutomationRetryEligibility.controlExecutions.incrementAndGet()
  }
}
