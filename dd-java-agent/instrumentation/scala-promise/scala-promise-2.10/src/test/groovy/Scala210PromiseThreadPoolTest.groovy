// Can't import both 'scala.concurrent.ExecutionContext' and '...ExecutionContext$', since CodeNarc
// is broken and complains about duplicate import statements even though it isn't.
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext$

class Scala210PromiseThreadPoolTest extends ScalaPromiseTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.fromExecutorService(Executors.newCachedThreadPool())
  }
}
