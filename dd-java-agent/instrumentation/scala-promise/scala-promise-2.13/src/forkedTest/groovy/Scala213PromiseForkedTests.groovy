import scala.concurrent.ExecutionContext

import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class Scala213PromiseCompletionPriorityForkJoinPoolForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutor(ForkJoinPool.commonPool())
  }
}

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class Scala213PromiseCompletionPriorityGlobalForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.global()
  }
}

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class Scala213PromiseCompletionPriorityScheduledThreadPoolForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutor(Executors.newScheduledThreadPool(5))
  }
}

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class Scala213PromiseCompletionPriorityThreadPoolForkedTest extends ScalaPromiseCompletionPriorityTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  }
}
