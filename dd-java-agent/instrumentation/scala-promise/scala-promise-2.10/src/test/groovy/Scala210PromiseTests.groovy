import scala.concurrent.ExecutionContext$

import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class Scala210PromiseForkJoinPoolTest extends ScalaPromiseTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.fromExecutor(ForkJoinPool.commonPool())
  }
}

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class Scala210PromiseGlobalTest extends ScalaPromiseTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.global()
  }
}

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class Scala210PromiseScheduledThreadPoolTest extends ScalaPromiseTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.fromExecutor(Executors.newScheduledThreadPool(5))
  }
}

@spock.lang.IgnoreIf({
  datadog.trace.agent.test.checkpoints.TimelineValidator.ignoreTest()
})
class Scala210PromiseThreadPoolTest extends ScalaPromiseTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.fromExecutorService(Executors.newCachedThreadPool())
  }
}
