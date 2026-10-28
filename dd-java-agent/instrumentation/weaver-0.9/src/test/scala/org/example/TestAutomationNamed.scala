package org.example

import cats.effect.IO
import datadog.trace.api.civisibility.NoAutoRetry
import datadog.trace.api.civisibility.PreventFailureSuppression
import weaver._

class NamedExcludedSuite(tag: String) extends SimpleIOSuite {
  override def name: String = "custom suite display name"

  test(TestName.fromString("named_test").tagged(tag)) {
    IO.pure(expect(true))
  }
}

object TestAutomationNamed extends NamedExcludedSuite(NoAutoRetry.TAG)

object TestAutomationProvider extends BaseCatsSuite with BaseSuiteClass {
  def getSuite: EffectSuite[IO] =
    new NamedExcludedSuite(PreventFailureSuppression.TAG)
}
