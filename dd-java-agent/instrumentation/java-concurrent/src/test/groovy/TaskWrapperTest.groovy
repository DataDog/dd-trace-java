import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.TaskWrapper

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.FutureTask
import java.util.function.Supplier

class TaskWrapperTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.profiling.enabled", "true")
    injectSysConfig("dd.profiling.experimental.queueing.time.enabled", "true")
    super.configurePreAgent()
  }

  def "check expected types instrumented and can be unwrapped"() {
    verify(new CompletableFuture.AsyncSupply(null, new TestSupplier()), TestSupplier)
    verify(new CompletableFuture.AsyncRun(null, new TestRunnable()), TestRunnable)
    verify(new FutureTask(new TestRunnable(), null), TestRunnable)
    verify(new FutureTask(new TestCallable()), TestCallable)
    verify(ForkJoinTask.adapt(new TestCallable()), TestCallable)
    verify(ForkJoinTask.adapt(new TestRunnable()), TestRunnable)
    verify(ForkJoinTask.adapt(new TestRunnable(), null), TestRunnable)
  }

  def verify(Object task, Class wrappedClass) {
    assert task instanceof TaskWrapper
    assert wrappedClass.isAssignableFrom(TaskWrapper.getUnwrappedType(task))
  }

  class TestSupplier implements Supplier {

    @Override
    Object get() {
      return null
    }
  }

  class TestRunnable implements Runnable {

    @Override
    void run() {
    }
  }

  class TestCallable implements Callable {

    @Override
    Object call() throws Exception {
      return null
    }
  }
}
