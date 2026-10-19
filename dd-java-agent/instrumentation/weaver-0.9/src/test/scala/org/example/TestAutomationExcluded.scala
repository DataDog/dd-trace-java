package org.example

import cats.effect.IO
import datadog.trace.api.civisibility.TestAutomationExclusions
import weaver._

object TestAutomationExcluded extends SimpleIOSuite {
  private def excluded(name: String): TestName = TestName
    .fromString(name)
    .tagged(TestAutomationExclusions.NO_AUTO_RETRY)
    .tagged(TestAutomationExclusions.PREVENT_FAILURE_SUPPRESSION)

  test(excluded("protected_failure")) {
    IO.pure(expect(false))
  }

  test(excluded("protected_skip")) {
    ignore("developer skip")
  }
  test("ordinary_tagged".tagged("customer_tag")) {
    IO.pure(expect(true))
  }
}
