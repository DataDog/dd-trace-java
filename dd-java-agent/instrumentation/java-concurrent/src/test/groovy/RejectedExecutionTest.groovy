import datadog.trace.agent.test.AgentTestRunner

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ForkJoinPool
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope

class RejectedExecutionTest extends AgentTestRunner {

  def "trace reported when FJP shutdown"() {
    // tests the shutdown state because it's easy to provoke without
    // spying the same points we instrument. This works the same way
    // in FJPs no matter the reason for rejection, and this could be
    // provoked (most of the time) by submitting a lot of tasks very
    // quickly
    setup:
    ForkJoinPool fjp = new ForkJoinPool()
    fjp.shutdownNow()
    AtomicBoolean rejected = new AtomicBoolean(false)

    when:
    runUnderTrace("parent") {
      try {
        fjp.submit({})
      } catch (RejectedExecutionException expected) {
        rejected.set(true)
      }
    }

    then:
    rejected.get()
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    TEST_WRITER.get(0).size() == 1
    TEST_WRITER.get(0).get(0).getOperationName() == "parent"
  }

  def "trace reported when thread pool shut down"() {
    setup:
    ExecutorService pool = Executors.newFixedThreadPool(1)
    AtomicBoolean rejected = new AtomicBoolean(false)
    pool.shutdownNow()

    when:
    runUnderTrace("parent") {
      try {
        pool.submit({})
      } catch (RejectedExecutionException expected) {
        rejected.set(true)
      }
    }

    then:
    rejected.get()
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    TEST_WRITER.get(0).size() == 1
    TEST_WRITER.get(0).get(0).getOperationName() == "parent"
  }

  def "trace reported when thread pool shut down with custom rejected execution handler"() {
    setup:
    ExecutorService pool = new ThreadPoolExecutor(1,
      1,
      0L,
      TimeUnit.SECONDS,
      new ArrayBlockingQueue<>(1),
      Executors.defaultThreadFactory(),
      new SwallowingRejectedExecutionHandler())
    pool.shutdownNow()

    when:
    runUnderTrace("parent") {
      activeScope().setAsyncPropagation(true)
      pool.submit({})
    }

    then:
    TEST_WRITER.waitForTraces(1)
    TEST_WRITER.size() == 1
    TEST_WRITER.get(0).size() == 1
    TEST_WRITER.get(0).get(0).getOperationName() == "parent"
  }


}
