import datadog.trace.agent.test.AgentTestRunner

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class FutureTaskContinuationTest extends AgentTestRunner {
  class SettableFuture extends FutureTask<String> {
    SettableFuture() {
      super({ "async result" })
    }

    @SuppressWarnings('UnnecessaryOverridingMethod')
    @Override
    void set(String value) {
      super.set(value)
    }

    @SuppressWarnings('UnnecessaryOverridingMethod')
    @Override
    void setException(Throwable cause) {
      super.setException(cause)
    }
  }

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
