package org.example

import cats.effect.IO
import datadog.trace.api.civisibility.NoAutoRetry
import datadog.trace.api.civisibility.PreventFailureSuppression
import java.util.concurrent.atomic.AtomicInteger
import weaver._

object TestAutomationDuplicateNames extends SimpleIOSuite {
  private val executions        = new AtomicInteger()
  private val ignoredExecutions = new AtomicInteger()

  def executionCount: Int        = executions.get()
  def ignoredExecutionCount: Int = ignoredExecutions.get()

  private val ignoredName = "ignored_collision"
  test(TestName.fromString(ignoredName).tagged(NoAutoRetry.TAG).ignore) {
    ignoredExecutions.incrementAndGet()
    IO.pure(expect(false))
  }
  test(TestName.fromString(ignoredName).tagged(PreventFailureSuppression.TAG).ignore) {
    ignoredExecutions.incrementAndGet()
    IO.pure(expect(false))
  }
  test(ignoredName) {
    executions.incrementAndGet()
    IO.pure(expect(true))
  }

  private val runnableName = "runnable_collision"
  test(TestName.fromString(runnableName).tagged(NoAutoRetry.TAG)) {
    executions.incrementAndGet()
    IO.pure(expect(false))
  }
  test(TestName.fromString(runnableName).tagged(PreventFailureSuppression.TAG)) {
    executions.incrementAndGet()
    IO.pure(expect(true))
  }
  test(runnableName) {
    executions.incrementAndGet()
    ignore("ordinary developer skip")
  }
  private val copiedName = TestName.fromString("copy_removed").tagged(NoAutoRetry.TAG)
  test(copiedName) {
    executions.incrementAndGet()
    IO.pure(expect(false))
  }
  test(copiedName.copy(tags = Set.empty[String])) {
    executions.incrementAndGet()
    IO.pure(expect(true))
  }
}

object TestAutomationDuplicatePureNames extends FunSuite {
  private val executions        = new AtomicInteger()
  private val ignoredExecutions = new AtomicInteger()

  def executionCount: Int        = executions.get()
  def ignoredExecutionCount: Int = ignoredExecutions.get()

  private val ignoredName = "ignored_collision"
  test(TestName.fromString(ignoredName).tagged(NoAutoRetry.TAG).ignore) {
    ignoredExecutions.incrementAndGet()
    expect(false)
  }
  test(ignoredName) {
    executions.incrementAndGet()
    expect(true)
  }

  private val runnableName = "runnable_collision"
  test(TestName.fromString(runnableName).tagged(NoAutoRetry.TAG)) {
    executions.incrementAndGet()
    expect(false)
  }
  test(TestName.fromString(runnableName).tagged(PreventFailureSuppression.TAG)) {
    executions.incrementAndGet()
    expect(true)
  }
  private val copiedName = TestName.fromString("copy_removed").tagged(NoAutoRetry.TAG)
  test(copiedName) {
    executions.incrementAndGet()
    expect(false)
  }
  test(copiedName.copy(tags = Set.empty[String])) {
    executions.incrementAndGet()
    expect(true)
  }
}
