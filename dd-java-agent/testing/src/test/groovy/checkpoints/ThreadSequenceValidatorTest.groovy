package checkpoints

import datadog.trace.agent.test.SpockRunner
import datadog.trace.agent.test.checkpoints.ThreadSequenceValidator
import org.junit.runner.RunWith
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

  def "check expected transitions"() {
    expect:
    ThreadSequenceValidator.SingleThreadTracker.transit(fromSpan, fromTask, signal) == [toSpan, toTask]

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
    Signal.END_SPAN     | SpanState.STARTED     | _                     || SpanState.ENDED      | _
    Signal.END_SPAN     | SpanState.SUSPENDED   | _                     || SpanState.ENDED      | _
    Signal.END_SPAN     | SpanState.RESUMED     | _                     || SpanState.ENDED      | _
    Signal.END_SPAN     | SpanState.ENDED       | _                     || SpanState.INVALID    | _
    Signal.SUSPEND_SPAN | SpanState.STARTED     | TaskState.INIT        || SpanState.SUSPENDED  | TaskState.INVALID
    Signal.SUSPEND_SPAN | SpanState.STARTED     | TaskState.ACTIVE      || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.STARTED     | TaskState.INACTIVE    || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.STARTED     | TaskState.FINISHED    || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.RESUMED     | TaskState.INIT        || SpanState.SUSPENDED  | TaskState.INVALID
    Signal.SUSPEND_SPAN | SpanState.RESUMED     | TaskState.ACTIVE      || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.RESUMED     | TaskState.INACTIVE    || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.RESUMED     | TaskState.FINISHED    || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.SUSPENDED   | TaskState.INIT        || SpanState.SUSPENDED  | TaskState.INVALID
    Signal.SUSPEND_SPAN | SpanState.SUSPENDED   | TaskState.ACTIVE      || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.SUSPENDED   | TaskState.INACTIVE    || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.SUSPENDED   | TaskState.FINISHED    || SpanState.SUSPENDED  | TaskState.INACTIVE
    Signal.SUSPEND_SPAN | SpanState.INIT        | _                     || SpanState.INVALID    | _
    Signal.SUSPEND_SPAN | SpanState.ENDED       | _                     || SpanState.INVALID    | _
    Signal.RESUME_SPAN  | SpanState.INIT        | TaskState.INIT        || SpanState.RESUMED    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.INIT        | TaskState.INACTIVE    || SpanState.RESUMED    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.INIT        | TaskState.ACTIVE      || SpanState.RESUMED    | TaskState.INVALID
    Signal.RESUME_SPAN  | SpanState.INIT        | TaskState.FINISHED    || SpanState.RESUMED    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.SUSPENDED   | TaskState.INIT        || SpanState.RESUMED    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.SUSPENDED   | TaskState.INACTIVE    || SpanState.RESUMED    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.SUSPENDED   | TaskState.ACTIVE      || SpanState.RESUMED    | TaskState.INVALID
    Signal.RESUME_SPAN  | SpanState.SUSPENDED   | TaskState.FINISHED    || SpanState.RESUMED    | TaskState.ACTIVE
    Signal.RESUME_SPAN  | SpanState.STARTED     | _                     || SpanState.INVALID    | _
    Signal.RESUME_SPAN  | SpanState.ENDED       | _                     || SpanState.INVALID    | _
    Signal.RESUME_SPAN  | SpanState.RESUMED     | _                     || SpanState.INVALID    | _
    Signal.START_TASK   | _                     | TaskState.INIT        || _                    | TaskState.ACTIVE
    Signal.START_TASK   | _                     | TaskState.INACTIVE    || _                    | TaskState.ACTIVE
    Signal.START_TASK   | _                     | TaskState.ACTIVE      || _                    | TaskState.INVALID
    Signal.START_TASK   | _                     | TaskState.FINISHED    || _                    | TaskState.INVALID
    Signal.END_TASK     | _                     | TaskState.INACTIVE    || _                    | TaskState.FINISHED
    Signal.END_TASK     | _                     | TaskState.ACTIVE      || _                    | TaskState.FINISHED
    Signal.END_TASK     | _                     | TaskState.INIT        || _                    | TaskState.INVALID
    Signal.END_TASK     | _                     | TaskState.FINISHED    || _                    | TaskState.INVALID
  }
}
