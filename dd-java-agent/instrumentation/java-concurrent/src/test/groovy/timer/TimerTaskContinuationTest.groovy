package timer

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan

import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import spock.lang.Shared

import java.time.Instant
import java.util.concurrent.CountDownLatch

class TimerTaskContinuationTest extends InstrumentationSpecification {
  @Shared
  Timer timer = new Timer()

  TimerTask timerTask

  CountDownLatch runLatch = new CountDownLatch(1)

  @Override
  boolean useStrictTraceWrites() {
    false
  }

  @Override
  def setup() {
    timerTask = new TimerTask() {
        @Override
        void run() {
          AgentSpan span = startSpan("test", "child")
          AgentScope scope = activateSpan(span)
          try {
            span.finish()
          } finally {
            scope.close()
            runLatch.countDown()
          }
        }
      }
  }

  def "test continuation activated when TimerTask runs (long)"() {
    when:
    runUnderTrace("parent", {
      timer.schedule(timerTask, 100)
    })
    runLatch.await()


    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        basicSpan(it, "parent")
        basicSpan(it, "child", trace(0).get(0))
      }
    }
  }

  def "test continuation activated when TimerTask runs (date)"() {
    when:
    runUnderTrace("parent", {
      timer.schedule(timerTask, Instant.now().plusMillis(100).toDate())
    })
    runLatch.await()

    then:
    assertTraces(1) {
      sortSpansByStart()
      trace(2) {
        basicSpan(it, "parent")
        basicSpan(it, "child", trace(0).get(0))
      }
    }
  }

  def "test continuation canceled when TimerTask is canceled"() {
    when:
    runUnderTrace("parent", {
      timer.schedule(timerTask, 1000)
    })

    then:
    assert timerTask.cancel()
    assertTraces(1) {
      trace(1) {
        basicSpan(it, "parent")
      }
    }
  }

  def "test not propagated when scheduled more than once"() {
    when:
    runUnderTrace("parent", {
      timer.schedule(timerTask, 100, 1000)
    })
    runLatch.await()
    timerTask.cancel()

    then:
    assertTraces(2) {
      trace(1) {
        basicSpan(it, "parent")
      }
      trace(1) {
        basicSpan(it, "child")
      }
    }
  }
}
