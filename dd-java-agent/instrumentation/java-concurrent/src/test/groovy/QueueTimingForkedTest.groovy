import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.TestProfilingContextIntegration
import datadog.trace.api.Platform
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling

import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.LinkedBlockingQueue

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class QueueTimingForkedTest extends AgentTestRunner {

  @Override
  protected void configurePreAgent() {
    // required for enabling the unwrapping instrumentation to get the relevant non-carrier class names
    injectSysConfig("dd.profiling.enabled", "true")
    injectSysConfig("dd.profiling.queueing.time.enabled", "true")
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
    verify(LinkedBlockingQueue.name)

    when:
    runUnderTrace("parent", {
      fjp.submit(new TestRunnable()).get()
    })

    then:
    // flaky before JDK21
    if (Platform.isJavaVersionAtLeast(21)) {
      verify("java.util.concurrent.ForkJoinPool\$WorkQueue")
    }

    cleanup:
    executor.shutdown()
    fjp.shutdown()
    TEST_PROFILING_CONTEXT_INTEGRATION.closedTimings.clear()
  }

  void verify(expectedQueueType) {
    assert TEST_PROFILING_CONTEXT_INTEGRATION.isBalanced()
    assert !TEST_PROFILING_CONTEXT_INTEGRATION.closedTimings.isEmpty()
    int numAsserts = 0
    while (!TEST_PROFILING_CONTEXT_INTEGRATION.closedTimings.isEmpty()) {
      def timing = TEST_PROFILING_CONTEXT_INTEGRATION.closedTimings.takeFirst() as TestProfilingContextIntegration.TestQueueTiming
      if (!(timing.task as Class).simpleName.isEmpty()) {
        assert timing != null
        assert timing.task == TestRunnable
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
