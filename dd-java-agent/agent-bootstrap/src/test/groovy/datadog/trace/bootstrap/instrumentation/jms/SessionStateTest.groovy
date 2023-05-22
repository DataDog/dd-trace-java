package datadog.trace.bootstrap.instrumentation.jms

import datadog.trace.bootstrap.instrumentation.api.AgentSpan
import datadog.trace.test.util.DDSpecification

import java.util.concurrent.CountDownLatch

class SessionStateTest extends DDSpecification {

  def "commit transaction"() {
    setup:
    def sessionState = new SessionState(0, true)
    def span1 = Mock(AgentSpan)
    def span2 = Mock(AgentSpan)
    when:
    sessionState.finishOnCommit(span1)
    sessionState.finishOnCommit(span2)
    then:
    0 * span1.finish()
    0 * span2.finish()
    sessionState.capturedSpanCount == 2
    when: "transaction committed"
    sessionState.onCommitOrRollback()
    then: "spans finished and queues empty"
    1 * span1.finish()
    1 * span2.finish()
    sessionState.capturedSpanCount == 0
  }

  def "when buffer overflows, spans are finished eagerly"() {
    setup:
    def sessionState = new SessionState(0, false)
    def span1 = Mock(AgentSpan)
    def span2 = Mock(AgentSpan)
    when: "fill the buffer"
    for (int i = 0; i < SessionState.MAX_CAPTURED_SPANS; ++i) {
      sessionState.finishOnCommit(span1)
    }
    then: "spans are not finished on entry"
    0 * span1.finish()
    sessionState.capturedSpanCount == SessionState.MAX_CAPTURED_SPANS
    when: "buffer overflows"
    sessionState.finishOnCommit(span2)
    then: "span is finished on entry"
    1 * span2.finish()
    sessionState.capturedSpanCount == SessionState.MAX_CAPTURED_SPANS
    when: "commit and add span"
    sessionState.onCommitOrRollback()
    sessionState.finishOnCommit(span2)
    then: "span is enqueued and not finished"
    0 * span2.finish()
    sessionState.capturedSpanCount == 1
  }

  def "stale time-in-queue spans are evicted from session"() {
    setup:
    def started = new CountDownLatch(100)
    def stopped = new CountDownLatch(1)
    def sessionState = new SessionState(0, true)
    def finishingTimes = []
    when: "add time-in-queue spans without triggering eviction"
    def workers = (1..100).collect {
      def span = Mock(AgentSpan)
      def startMillis = it * 1000
      span.finish() >> { finishingTimes += startMillis }
      span.startTime >> startMillis
      Thread.start {
        sessionState.setTimeInQueueSpan(0, span)
        started.countDown()
        stopped.await()
      }
    }
    started.await()
    then: "nothing has been finished yet"
    sessionState.timeInQueueSpanCount == 100
    finishingTimes == []
    when: "trigger eviction of oldest time-in-queue spans"
    (1..1).each { Thread.start { sessionState.setTimeInQueueSpan(0, Mock(AgentSpan)) }.join() }
    then: "ten oldest time-in-queue spans should have been finished"
    sessionState.timeInQueueSpanCount == 91
    finishingTimes as Set == (1..10).collect { it * 1000 } as Set
    when: "trigger eviction of stopped workers"
    stopped.countDown()
    workers.each { it.join() }
    (1..10).each { Thread.start { sessionState.setTimeInQueueSpan(0, Mock(AgentSpan)) }.join() }
    then: "all worker time-in-queue spans should have been finished"
    sessionState.timeInQueueSpanCount == 1
    finishingTimes as Set == (1..100).collect { it * 1000 } as Set
  }
}
