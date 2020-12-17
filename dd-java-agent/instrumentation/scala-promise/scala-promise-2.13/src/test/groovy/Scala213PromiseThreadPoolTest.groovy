import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

class Scala213PromiseThreadPoolTest extends ScalaPromiseTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  }
}
