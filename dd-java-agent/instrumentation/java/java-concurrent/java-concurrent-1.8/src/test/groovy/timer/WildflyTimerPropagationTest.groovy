package timer

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

import datadog.trace.agent.test.InstrumentationSpecification
import org.jboss.as.ejb3.timerservice.TimerServiceImpl

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WildflyTimerPropagationTest extends InstrumentationSpecification {
  def "calendar timeout is independent and ordinary timer propagation is restored"() {
    setup:
    def timer = new Timer(true)
    def done = new CountDownLatch(2)
    def calendarTask = new TimerTask() {
        void run() {
          try {
            runUnderTrace("calendar", {})
          } finally {
            done.countDown()
          }
        }
      }
    def ordinaryTask = new TimerTask() {
        void run() {
          try {
            runUnderTrace("ordinary", {})
          } finally {
            done.countDown()
          }
        }
      }

    when:
    runUnderTrace("request", {
      new TimerServiceImpl().scheduleTimeout(timer, calendarTask)
      timer.schedule(ordinaryTask, 10)
    })

    then:
    done.await(10, TimeUnit.SECONDS)
    assertTraces(2) {
      sortSpansByStart()
      trace(2) {
        basicSpan(it, "request")
        basicSpan(it, "ordinary", span(0))
      }
      trace(1) {
        basicSpan(it, "calendar")
      }
    }

    cleanup:
    timer.cancel()
  }
}
