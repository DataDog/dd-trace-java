import datadog.environment.JavaVirtualMachine
import datadog.environment.OperatingSystem
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.TaskWrapper

import java.util.concurrent.Callable
import java.util.concurrent.ForkJoinTask
import java.util.concurrent.FutureTask

class TaskUnwrappingForkedTest extends InstrumentationSpecification {

  // Hypothesis check: Oracle JDK 8 on linux-aarch64 SIGSEGVs inside Parallel Old GC
  // when the profiler is started in-process. Disable profiling on that platform to
  // see whether the crash is profiling-related. See crash.md.
  private static final String PROFILING_ENABLED =
  String.valueOf(!(JavaVirtualMachine.isOracleJDK8() && OperatingSystem.architecture().isArm64()))

  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.profiling.enabled", PROFILING_ENABLED)
    injectSysConfig("dd.profiling.queueing.time.enabled", PROFILING_ENABLED)
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
