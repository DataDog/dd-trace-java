import scala.concurrent.ExecutionContext

class Scala210PromiseGlobalTest extends ScalaPromiseTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    // Can't use "import scala.concurrent.ExecutionContext$" since CodeNarc is broken and
    // complains about duplicate import statements even though it isn't
    return scala.concurrent.ExecutionContext$.MODULE$.global()
  }

  @Override
  boolean picksUpCompletingScope() {
    return false
  }
}
