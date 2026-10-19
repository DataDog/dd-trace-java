package org.example

import cats.effect.IO
import datadog.trace.api.civisibility.TestAutomationExclusions
import weaver._

class NamedExcludedSuite(tag: String) extends SimpleIOSuite {
  override def name: String = "custom suite display name"

  test(TestName.fromString("named_test").tagged(tag)) {
    IO.pure(expect(true))
  }
}

object TestAutomationNamed extends NamedExcludedSuite(TestAutomationExclusions.NO_AUTO_RETRY)

object TestAutomationProvider extends BaseCatsSuite with BaseSuiteClass {
  def getSuite: EffectSuite[IO] =
    new NamedExcludedSuite(TestAutomationExclusions.PREVENT_FAILURE_SUPPRESSION)
}
