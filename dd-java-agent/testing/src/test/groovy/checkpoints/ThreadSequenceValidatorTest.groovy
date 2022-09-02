package checkpoints

import datadog.trace.agent.test.SpockRunner
import org.junit.runner.RunWith
import spock.lang.Specification

import static datadog.trace.agent.test.checkpoints.ThreadSequenceValidator.*

@RunWith(SpockRunner)
class ThreadSequenceValidatorTest extends Specification {
  def tracker

  void setup() {
    tracker = new SingleThreadTracker()
  }

  def "start-end span"() {
    expect:
    tracker.startSpan()
    tracker.endSpan()
  }

  def "end span"() {
    expect:
    !tracker.endSpan()
  }

  def "start from resume"() {
    expect:
    tracker.resumeSpan()   // resume span S1; start task T1
    tracker.suspendSpan()  // start migration of T2
    tracker.suspendSpan()  // start migration of T3
    tracker.endTask()      // end task T1
    tracker.resumeSpan()   // start task T2
    tracker.endTask()      // end task T2
    tracker.resumeSpan()   // start task T3
    tracker.endTask()      // end task T3
    tracker.suspendSpan()  // start migration of T4
    tracker.resumeSpan()   // start task T4
    tracker.endTask()      // end task T4
    tracker.endSpan()      // end span S1
  }

  def "double end"() {
    expect:
    tracker.resumeSpan()
    tracker.endTask()
    tracker.resumeSpan()
    tracker.suspendSpan()
    tracker.resumeSpan()
    tracker.suspendSpan()
    tracker.endTask()
    tracker.endTask()
  }

  def "double resume"() {
    expect:
    tracker.resumeSpan()
    tracker.resumeSpan()
  }

  def "resume after end span"() {
    expect:
    tracker.startSpan()
    tracker.suspendSpan()
    tracker.endSpan()
    tracker.resumeSpan()
    tracker.endTask()
  }

  def "check expected transitions"() {
    expect:
    SingleThreadTracker.transit(fromSpan, signal) == toSpan

    where:
    signal              | fromSpan              || toSpan
    Signal.START_SPAN   | SpanState.INIT        || SpanState.STARTED
    Signal.START_SPAN   | SpanState.STARTED     || SpanState.INVALID
    Signal.START_SPAN   | SpanState.SUSPENDED   || SpanState.INVALID
    Signal.START_SPAN   | SpanState.RESUMED     || SpanState.INVALID
    Signal.START_SPAN   | SpanState.ENDED       || SpanState.INVALID
    Signal.END_SPAN     | SpanState.INIT        || SpanState.INVALID
    Signal.END_SPAN     | SpanState.STARTED     || SpanState.ENDED
    Signal.END_SPAN     | SpanState.SUSPENDED   || SpanState.ENDED
    Signal.END_SPAN     | SpanState.RESUMED     || SpanState.ENDED
    Signal.END_SPAN     | SpanState.ENDED       || SpanState.INVALID
    Signal.SUSPEND_SPAN | SpanState.INIT        || SpanState.INVALID
    Signal.SUSPEND_SPAN | SpanState.STARTED     || SpanState.SUSPENDED
    Signal.SUSPEND_SPAN | SpanState.SUSPENDED   || SpanState.SUSPENDED
    Signal.SUSPEND_SPAN | SpanState.RESUMED     || SpanState.SUSPENDED
    Signal.SUSPEND_SPAN | SpanState.ENDED       || SpanState.SUSPENDED
    Signal.RESUME_SPAN  | _                     || SpanState.RESUMED
  }
}
