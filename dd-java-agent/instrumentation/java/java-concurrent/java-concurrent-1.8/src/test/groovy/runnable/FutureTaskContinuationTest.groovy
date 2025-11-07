package runnable

import datadog.trace.agent.test.InstrumentationSpecification

import java.util.concurrent.ExecutionException

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class FutureTaskContinuationTest extends InstrumentationSpecification {
  SettableFuture future

  @Override
  def setup() {
    runUnderTrace("parent") {
      future = new SettableFuture()
    }
  }

  def "test continuation activated when FutureTask runs"() {
    when:
    future.run()

    then:
    future.get() == "async result"
    assertTraces(1) {
      trace(1) {
        basicSpan(it, "parent")
      }
    }
  }

  def "test continuation canceled when FutureTask is canceled"() {
    when:
    future.cancel(true)

    then:
    assertTraces(1) {
      trace(1) {
        basicSpan(it, "parent")
      }
    }
  }

  def "test continuation canceled when FutureTask value is set without running"() {
    when:
    future.set("set result")

    then:
    future.get() == "set result"
    assertTraces(1) {
      trace(1) {
        basicSpan(it, "parent")
      }
    }
  }

  def "test continuation canceled when FutureTask exception is set without running"() {
    when:
    future.setException(new RuntimeException("expected"))

    and:
    future.get()

    then:
    ExecutionException e = thrown()
    e.getCause().message == "expected"

    assertTraces(1) {
      trace(1) {
        basicSpan(it, "parent")
      }
    }
  }
}
