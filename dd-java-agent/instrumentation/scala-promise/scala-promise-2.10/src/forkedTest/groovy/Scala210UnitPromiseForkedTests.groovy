// Can't import both 'scala.concurrent.ExecutionContext' and '...ExecutionContext$', since CodeNarc
// is broken and complains about duplicate import statements even though it isn't.
import scala.concurrent.ExecutionContext$

class Scala210UnitPromiseForkedTest extends ScalaUnitPromiseTestNoPropagation {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.global()
  }

  @Override
  protected boolean hasUnitPromise() {
    scala.util.Properties.versionNumberString().startsWith("2.12.")
  }
}

class Scala210UnitPromiseCheckInstrumentationForkedTest extends ScalaUnitPromiseTestPropagation {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.global()
  }

  @Override
  protected boolean hasUnitPromise() {
    scala.util.Properties.versionNumberString().startsWith("2.12.")
  }
}
