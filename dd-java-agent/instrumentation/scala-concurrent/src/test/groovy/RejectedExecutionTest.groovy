import datadog.trace.agent.test.InstrumentationSpecification
import scala.concurrent.forkjoin.ForkJoinPool

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class RejectedExecutionTest extends InstrumentationSpecification {

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
}
