import scala.concurrent.ExecutionContext

import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool

class Scala213PromiseCompletionPriorityForkJoinPoolForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutor(ForkJoinPool.commonPool())
  }
}

class Scala213PromiseCompletionPriorityGlobalForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.global()
  }
}

class Scala213PromiseCompletionPriorityScheduledThreadPoolForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutor(Executors.newScheduledThreadPool(5))
  }
}

class Scala213PromiseCompletionPriorityThreadPoolForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  }
}
