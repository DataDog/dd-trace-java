// Can't import both 'scala.concurrent.ExecutionContext' and '...ExecutionContext$', since CodeNarc
// is broken and complains about duplicate import statements even though it isn't.
import scala.concurrent.ExecutionContext$

class Scala210PromiseGlobalTest extends ScalaPromiseTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.global()
  }
}
