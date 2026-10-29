import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.jfr.InstrumentationBasedProfiling

import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class QueueTimingReadinessForkedTest extends InstrumentationSpecification {
  @Override
  protected void configurePreAgent() {
    injectSysConfig("dd.profiling.enabled", "true")
    injectSysConfig("dd.profiling.queueing.time.enabled", "true")
    super.configurePreAgent()
  }

  def "queue metadata is read only after JFR is ready while tracing always propagates"() {
    setup:
    def queue = new CountingQueue()
    def executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS, queue)
    assert !InstrumentationBasedProfiling.isJFRReady()

    when:
    runTasks(executor, "before")

    then:
    queue.sizeCalls.get() == 0
    TEST_PROFILING_CONTEXT_INTEGRATION.closedTimings.isEmpty()
    assertTraces(2) {
      trace(1) {
        basicSpan(it, "execute-before")
      }
      trace(1) {
        basicSpan(it, "submit-before")
      }
    }

    when:
    TEST_WRITER.clear()
    InstrumentationBasedProfiling.enableInstrumentationBasedProfiling()
    runTasks(executor, "after")

    then:
    queue.sizeCalls.get() == 2
    assertTraces(2) {
      trace(1) {
        basicSpan(it, "execute-after")
      }
      trace(1) {
        basicSpan(it, "submit-after")
      }
    }
    TEST_PROFILING_CONTEXT_INTEGRATION.isBalanced()
    TEST_PROFILING_CONTEXT_INTEGRATION.closedTimings.size() == 2
    TEST_PROFILING_CONTEXT_INTEGRATION.closedTimings.every {
      it.queue == CountingQueue && it.scheduler == ThreadPoolExecutor && it.queueLength >= 0
    }

    cleanup:
    executor.shutdownNow()
    assert executor.awaitTermination(5, TimeUnit.SECONDS)
    TEST_PROFILING_CONTEXT_INTEGRATION.closedTimings.clear()
  }

  private static void runTasks(ThreadPoolExecutor executor, String phase) {
    runUnderTrace("execute-" + phase) {
      def expectedSpanId = AgentTracer.activeSpan().spanId
      def task = new ContextCheckingTask()
      executor.execute(task)
      assert task.done.await(5, TimeUnit.SECONDS)
      assert task.spanId == expectedSpanId
    }
    runUnderTrace("submit-" + phase) {
      def expectedSpanId = AgentTracer.activeSpan().spanId
      def task = new ContextCheckingTask()
      executor.submit(task).get(5, TimeUnit.SECONDS)
      assert task.spanId == expectedSpanId
    }
  }

  static class ContextCheckingTask implements Runnable {
    final CountDownLatch done = new CountDownLatch(1)
    volatile long spanId

    @Override
    void run() {
      def span = AgentTracer.activeSpan()
      spanId = span == null ? 0 : span.spanId
      done.countDown()
    }
  }

  static class CountingQueue extends LinkedBlockingQueue<Runnable> {
    final AtomicInteger sizeCalls = new AtomicInteger()

    @Override
    int size() {
      sizeCalls.incrementAndGet()
      return super.size()
    }

    @Override
    boolean isEmpty() {
      // Executor housekeeping must not count as a queue-timing metadata read.
      return super.size() == 0
    }
  }
}
