import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext

import java.util.concurrent.ForkJoinPool

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class Scala213PromiseForkJoinPoolTest extends ScalaPromiseTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutor(ForkJoinPool.commonPool())
  }
}

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class Scala213PromiseGlobalTest extends ScalaPromiseTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.global()
  }
}

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class Scala213PromiseScheduledThreadPoolTest extends ScalaPromiseTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutor(Executors.newScheduledThreadPool(5))
  }
}

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class Scala213PromiseThreadPoolTest extends ScalaPromiseTestBase {
  @Override
  protected ExecutionContext getExecutionContext() {
    return ExecutionContext.fromExecutorService(Executors.newCachedThreadPool())
  }
}
