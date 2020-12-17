import scala.concurrent.ExecutionContext

class Scala213PromiseGlobalTest extends ScalaPromiseTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.global()
  }
}
