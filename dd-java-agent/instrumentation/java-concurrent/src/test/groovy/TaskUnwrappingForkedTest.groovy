import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.TaskWrapper

import java.util.concurrent.Callable
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.FutureTask

class TaskUnwrappingForkedTest extends InstrumentationSpecification {

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.profiling.enabled", "true")
    injectSysConfig("dd.profiling.queueing.time.enabled", "true")
    super.configurePreAgent()
  }

  def "check expected types instrumented and can be unwrapped (#iterationIndex)"() {
    expect:
    task instanceof TaskWrapper
    type.isAssignableFrom(TaskWrapper.getUnwrappedType(task))
    // stick to public APIs here to make running these tests on more recent JDKs simpler,
    // even if this means missing some coverage (e.g. CompletableFuture$AsyncSupply)
    where:
    task                                                        | type
    new FutureTask(new TestRunnable(), null)                    | TestRunnable
    new FutureTask(new TestCallable())                          | TestCallable
    ForkJoinTask.adapt(new TestCallable())                      | TestCallable
    ForkJoinTask.adapt(new TestRunnable())                      | TestRunnable
    ForkJoinTask.adapt(new TestRunnable(), null)                | TestRunnable
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
