import java.util.concurrent.ForkJoinPool
import scala.concurrent.ExecutionContext

class Scala213ForkJoinPoolPromiseTest extends ScalaPromiseTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutor(ForkJoinPool.commonPool())
  }
}
