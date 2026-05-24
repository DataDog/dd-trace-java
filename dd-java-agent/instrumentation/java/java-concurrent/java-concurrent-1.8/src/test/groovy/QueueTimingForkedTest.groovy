import datadog.environment.JavaVirtualMachine
import datadog.environment.OperatingSystem
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.agent.test.TestProfilingContextIntegration
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling

import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.LinkedBlockingQueue

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class QueueTimingForkedTest extends InstrumentationSpecification {

  // Hypothesis check: Oracle JDK 8 on linux-aarch64 SIGSEGVs inside Parallel Old GC
  // when the profiler is started in-process. Disable profiling on that platform to
  // see whether the crash is profiling-related. See crash.md.
  private static final String PROFILING_ENABLED =
  String.valueOf(!(JavaVirtualMachine.isOracleJDK8() && OperatingSystem.architecture().isArm64()))

  @Override
  protected void configurePreAgent() {
    // required for enabling the unwrapping instrumentation to get the relevant non-carrier class names
    injectSysConfig("dd.profiling.enabled", PROFILING_ENABLED)
    injectSysConfig("dd.profiling.queueing.time.enabled", PROFILING_ENABLED)
    InstrumentationBasedProfiling.enableInstrumentationBasedProfiling()
    super.configurePreAgent()
  }

  def "test queue timing with submit"() {
    setup:
    def executor = Executors.newSingleThreadExecutor()
    def fjp = new ForkJoinPool(1)

    when:
    runUnderTrace("parent", {
      executor.submit(new TestRunnable()).get()
    })

    then:
    verify(LinkedBlockingQueue.name, 'TestRunnable')

    when:
    runUnderTrace("parent", {
      fjp.submit(new TestRunnable()).get()
    })

    then:
    // Starting from Java 24, ForkJoinPool will wrap a Runnable with the {@code java.util.concurrent.ForkJoinTask$AdaptedInterruptibleRunnable} class
    String expectedTaskClassName = JavaVirtualMachine.isJavaVersionAtLeast(24) ? 'AdaptedInterruptibleRunnable' : 'TestRunnable'

    // flaky before JDK21
    if (JavaVirtualMachine.isJavaVersionAtLeast(21)) {
      verify("java.util.concurrent.ForkJoinPool\$WorkQueue", expectedTaskClassName)
    }

    cleanup:
    executor.shutdown()
    fjp.shutdown()
    TEST_PROFILING_CONTEXT_INTEGRATION.closedTimings.clear()
  }

  void verify(expectedQueueType, expectedTaskClassName) {
    assert TEST_PROFILING_CONTEXT_INTEGRATION.isBalanced()
    assert !TEST_PROFILING_CONTEXT_INTEGRATION.closedTimings.isEmpty()
    int numAsserts = 0
    while (!TEST_PROFILING_CONTEXT_INTEGRATION.closedTimings.isEmpty()) {
      def timing = TEST_PROFILING_CONTEXT_INTEGRATION.closedTimings.takeFirst() as TestProfilingContextIntegration.TestQueueTiming
      if (!(timing.task as Class).simpleName.isEmpty()) {
        assert timing != null
        assert timing.task.simpleName == expectedTaskClassName
        assert timing.scheduler != null
        assert timing.origin == Thread.currentThread()
        assert timing.queueLength >= 0
        assert timing.queue.name == expectedQueueType
        numAsserts++
      }
    }
    assert numAsserts > 0
  }


  class TestRunnable implements Runnable {
    @Override
    void run() {}
  }
}
