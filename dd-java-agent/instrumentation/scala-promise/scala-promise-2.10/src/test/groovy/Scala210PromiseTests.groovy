import scala.concurrent.ExecutionContext$

import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool

class Scala210PromiseForkJoinPoolTest extends ScalaPromiseTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.fromExecutor(ForkJoinPool.commonPool())
  }
}

class Scala210PromiseGlobalTest extends ScalaPromiseTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.global()
  }
}

class Scala210PromiseScheduledThreadPoolTest extends ScalaPromiseTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.fromExecutor(Executors.newScheduledThreadPool(5))
  }
}

class Scala210PromiseThreadPoolTest extends ScalaPromiseTestBase {
  @Override
  protected scala.concurrent.ExecutionContext getExecutionContext() {
    return ExecutionContext$.MODULE$.fromExecutorService(Executors.newCachedThreadPool())
  }
}
