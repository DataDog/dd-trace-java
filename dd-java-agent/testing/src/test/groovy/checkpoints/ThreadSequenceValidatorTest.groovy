package checkpoints

import datadog.trace.agent.test.SpockRunner
import datadog.trace.agent.test.checkpoints.ThreadSequenceValidator
import org.junit.runner.RunWith
import org.spockframework.lang.Wildcard
import spock.lang.Specification

import static datadog.trace.agent.test.checkpoints.ThreadSequenceValidator.*

@RunWith(SpockRunner)
class ThreadSequenceValidatorTest extends Specification {
  def tracker

  void setup() {
    tracker = new ThreadSequenceValidator.SingleThreadTracker()
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
    stateEquals(ThreadSequenceValidator.SingleThreadTracker.transit(fromSpan, fromTask, signal), [toSpan, toTask])

    where:
    signal              | fromSpan              | fromTask              || toSpan               | toTask
    Signal.START_SPAN   | SpanState.INIT        | TaskState.INIT        || SpanState.STARTED    | TaskState.ACTIVE
    Signal.START_SPAN   | SpanState.INIT        | TaskState.INACTIVE    || SpanState.STARTED    | TaskState.ACTIVE
    Signal.START_SPAN   | SpanState.INIT        | TaskState.ACTIVE      || SpanState.STARTED    | TaskState.INVALID
    Signal.START_SPAN   | SpanState.INIT        | TaskState.FINISHED    || SpanState.STARTED    | TaskState.INVALID
    Signal.START_SPAN   | SpanState.STARTED     | _                     || SpanState.INVALID    | _
    Signal.START_SPAN   | SpanState.SUSPENDED   | _                     || SpanState.INVALID    | _
    Signal.START_SPAN   | SpanState.RESUMED     | _                     || SpanState.INVALID    | _
    Signal.START_SPAN   | SpanState.ENDED       | _                     || SpanState.INVALID    | _
    Signal.END_SPAN     | SpanState.INIT        | _                     || SpanState.INVALID    | _
    Signal.END_SPAN     | SpanState.STARTED     | _                     || SpanState.ENDED      | TaskState.INACTIVE
    Signal.END_SPAN     | SpanState.SUSPENDED   | _                     || SpanState.ENDED      | TaskState.INACTIVE
    Signal.END_SPAN     | SpanState.RESUMED     | _                     || SpanState.ENDED      | TaskState.INACTIVE
    Signal.END_SPAN     | SpanState.ENDED       | _                     || SpanState.INVALID    | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.STARTED     | TaskState.INIT        || SpanState.INVALID    | TaskState.INIT
    Signal.SUSPEND_SPAN | SpanState.STARTED     | TaskState.ACTIVE      || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.STARTED     | TaskState.INACTIVE    || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.STARTED     | TaskState.FINISHED    || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.RESUMED     | TaskState.INIT        || SpanState.INVALID    | TaskState.INIT
    Signal.SUSPEND_SPAN | SpanState.RESUMED     | TaskState.ACTIVE      || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.RESUMED     | TaskState.INACTIVE    || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.RESUMED     | TaskState.FINISHED    || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.SUSPENDED   | TaskState.INIT        || SpanState.INVALID    | TaskState.INIT
    Signal.SUSPEND_SPAN | SpanState.SUSPENDED   | TaskState.ACTIVE      || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.SUSPENDED   | TaskState.INACTIVE    || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.SUSPENDED   | TaskState.FINISHED    || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.ENDED       | TaskState.ACTIVE      || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.ENDED       | TaskState.INACTIVE    || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.ENDED       | TaskState.INIT        || SpanState.INVALID    | TaskState.INIT
    Signal.SUSPEND_SPAN | SpanState.ENDED       | TaskState.FINISHED    || SpanState.INVALID    | TaskState.FINISHED
    Signal.SUSPEND_SPAN | SpanState.INIT        | _                     || SpanState.INVALID    | _
    Signal.RESUME_SPAN  | SpanState.INIT        | TaskState.INIT        || SpanState.RESUMED    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.INIT        | TaskState.INACTIVE    || SpanState.RESUMED    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.INIT        | TaskState.ACTIVE      || SpanState.INVALID    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.INIT        | TaskState.FINISHED    || SpanState.RESUMED    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.SUSPENDED   | TaskState.INIT        || SpanState.RESUMED    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.SUSPENDED   | TaskState.INACTIVE    || SpanState.RESUMED    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.SUSPENDED   | TaskState.ACTIVE      || SpanState.INVALID    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.SUSPENDED   | TaskState.FINISHED    || SpanState.RESUMED    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.RESUMED     | TaskState.FINISHED    || SpanState.RESUMED    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.RESUMED     | TaskState.ACTIVE      || SpanState.RESUMED    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.RESUMED     | TaskState.INIT        || SpanState.INVALID    | TaskState.INIT
    Signal.RESUME_SPAN  | SpanState.RESUMED     | TaskState.INACTIVE    || SpanState.INVALID    | TaskState.INACTIVE
    Signal.RESUME_SPAN  | SpanState.RESUMED     | TaskState.FINISHED    || SpanState.RESUMED    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.STARTED     | _                     || SpanState.INVALID    | _
    Signal.RESUME_SPAN  | SpanState.ENDED       | TaskState.INACTIVE    || SpanState.RESUMED    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.ENDED       | TaskState.INIT        || SpanState.INVALID    | _
    Signal.RESUME_SPAN  | SpanState.ENDED       | TaskState.ACTIVE      || SpanState.INVALID    | _
    Signal.RESUME_SPAN  | SpanState.ENDED       | TaskState.FINISHED    || SpanState.INVALID    | _
    Signal.START_TASK   | _                     | TaskState.INIT        || _                    | TaskState.ACTIVE
    Signal.START_TASK   | _                     | TaskState.INACTIVE    || _                    | TaskState.ACTIVE
    Signal.START_TASK   | _                     | TaskState.ACTIVE      || _                    | TaskState.INVALID
    Signal.START_TASK   | _                     | TaskState.FINISHED    || _                    | TaskState.INVALID
    Signal.END_TASK     | SpanState.RESUMED     | TaskState.FINISHED    || SpanState.RESUMED    | TaskState.FINISHED
    Signal.END_TASK     | SpanState.RESUMED     | TaskState.INIT        || _                    | TaskState.INVALID
    Signal.END_TASK     | SpanState.RESUMED     | TaskState.ACTIVE      || SpanState.RESUMED    | TaskState.FINISHED
    Signal.END_TASK     | SpanState.RESUMED     | TaskState.INACTIVE    || SpanState.RESUMED    | TaskState.FINISHED
    Signal.END_TASK     | _                     | TaskState.FINISHED    || _                    | TaskState.FINISHED
    Signal.END_TASK     | _                     | TaskState.INACTIVE    || _                    | TaskState.FINISHED
    Signal.END_TASK     | _                     | TaskState.ACTIVE      || _                    | TaskState.FINISHED
    Signal.END_TASK     | _                     | TaskState.INIT        || _                    | TaskState.INVALID
  }

  def stateEquals(def left, right) {
    def result = new boolean[2]
    if (left[0] instanceof Wildcard || right[0] instanceof Wildcard) {
      result[0] = true
    } else {
      result[0] = (left[0] == right[0])
    }
    if (left[1] instanceof Wildcard || right[1] instanceof Wildcard) {
      result[1] = true
    } else {
      result[1] = (left[1] == right[1])
    }
    return result[0] && result[1]
  }
}
