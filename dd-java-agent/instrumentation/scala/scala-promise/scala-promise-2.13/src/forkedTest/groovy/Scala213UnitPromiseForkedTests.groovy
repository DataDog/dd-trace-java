import scala.concurrent.ExecutionContext

class Scala213UnitPromiseForkedTest extends ScalaUnitPromiseTestNoPropagation {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.global()
  }
}

class Scala213UnitPromiseCheckInstrumentationForkedTest extends ScalaUnitPromiseTestPropagation {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.global()
  }
}
