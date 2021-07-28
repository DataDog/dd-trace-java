package checkpoints

import datadog.trace.agent.test.SpockRunner
import datadog.trace.agent.test.checkpoints.SpanContextTracker
import org.junit.runner.RunWith
import spock.lang.Specification

import static datadog.trace.agent.test.checkpoints.SpanContextTracker.SpanState.*
import static datadog.trace.agent.test.checkpoints.SpanContextTracker.TaskState.*

@RunWith(SpockRunner)
class SpanContextTrackerTest extends Specification {
  def tracker

  void setup() {
    tracker = new SpanContextTracker()
  }

  def "start-end span"() {
    expect:
    tracker.startSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    tracker.endSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
  }

  def "fork_join"() {
    expect:
    tracker.startSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    tracker.suspendTask()
    tracker.taskStack() == [MIGRATING, ACTIVE, TASK_INIT]
    tracker.suspendTask()
    tracker.taskStack() == [MIGRATING, MIGRATING, ACTIVE, TASK_INIT]
    tracker.resumeTask()
    tracker.taskStack() == [ACTIVE, MIGRATING, ACTIVE, TASK_INIT]
    tracker.endTask()
    tracker.taskStack() == [MIGRATING, ACTIVE, TASK_INIT]
    tracker.resumeTask()
    tracker.taskStack() == [ACTIVE, ACTIVE, TASK_INIT]
    tracker.suspendTask()
    tracker.taskStack() == [MIGRATING, ACTIVE, ACTIVE, TASK_INIT]
    tracker.resumeTask()
    tracker.taskStack() == [ACTIVE, ACTIVE, ACTIVE, TASK_INIT]
    tracker.suspendTask()
    tracker.taskStack() == [MIGRATING, ACTIVE, ACTIVE, ACTIVE, TASK_INIT]
    tracker.endTask()
    tracker.taskStack() == [MIGRATING, ACTIVE, ACTIVE, TASK_INIT]
    tracker.endTask()
    tracker.taskStack() == [MIGRATING, ACTIVE, TASK_INIT]
    tracker.endTask()
    tracker.taskStack() == [MIGRATING, TASK_INIT]
    tracker.endSpan()
    tracker.taskStack() == [MIGRATING, TASK_INIT]
    tracker.spanState == CLOSED
  }

  def "simple suspend_resume sequence"() {
    expect:
    tracker.startSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    tracker.suspendTask()
    tracker.taskStack() == [MIGRATING, ACTIVE, TASK_INIT]
    tracker.resumeTask()
    tracker.taskStack() == [ACTIVE, ACTIVE, TASK_INIT]
    tracker.endTask()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    tracker.endSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
  }

  def "recursive suspend_resume sequence"() {
    expect:
    tracker.startSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    tracker.suspendTask()
    tracker.taskStack() == [MIGRATING, ACTIVE, TASK_INIT]
    tracker.suspendTask()
    tracker.taskStack() == [MIGRATING, MIGRATING, ACTIVE, TASK_INIT]
    tracker.suspendTask()
    tracker.taskStack() == [MIGRATING, MIGRATING, MIGRATING, ACTIVE, TASK_INIT]
    tracker.resumeTask()
    tracker.taskStack() == [ACTIVE, MIGRATING, MIGRATING, ACTIVE, TASK_INIT]
    tracker.resumeTask()
    tracker.taskStack() == [ACTIVE, ACTIVE, MIGRATING, ACTIVE, TASK_INIT]
    tracker.endTask()
    tracker.taskStack() == [ACTIVE, MIGRATING, ACTIVE, TASK_INIT]
    tracker.resumeTask()
    tracker.taskStack() == [ACTIVE, ACTIVE, ACTIVE, TASK_INIT]
    tracker.endTask()
    tracker.taskStack() == [ACTIVE, ACTIVE, TASK_INIT]
    tracker.endTask()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    tracker.endSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
  }

  def "wrapping suspend_resume sequence"() {
    tracker.startSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    tracker.suspendTask()
    tracker.taskStack() == [MIGRATING, ACTIVE, TASK_INIT]
    tracker.suspendTask()
    tracker.taskStack() == [MIGRATING, MIGRATING, ACTIVE, TASK_INIT]
    tracker.resumeTask()
    tracker.taskStack() == [ACTIVE, MIGRATING, ACTIVE, TASK_INIT]
    tracker.resumeTask()
    tracker.taskStack() == [ACTIVE, ACTIVE, ACTIVE, TASK_INIT]
    tracker.endTask()
    tracker.taskStack() == [ACTIVE, ACTIVE, TASK_INIT]
    tracker.endTask()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
  }

  def "missing start"() {
    expect:
    !tracker.suspendTask(true)
    !tracker.resumeTask(true)
    !tracker.endTask(true)
    !tracker.endSpan(true)
  }

  def "missing suspend"() {
    expect:
    tracker.startSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    !tracker.resumeTask()
  }

  def "missing resume"() {
    expect:
    tracker.startSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    tracker.suspendTask()
    tracker.taskStack() == [MIGRATING, ACTIVE, TASK_INIT]
    tracker.endTask()
    tracker.taskStack() == [MIGRATING, TASK_INIT]
    tracker.endSpan()
    tracker.taskStack() == [MIGRATING, TASK_INIT]
  }

  def "out_of_band end_span"() {
    expect:
    tracker.startSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    tracker.endSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    tracker.endTask()
    tracker.taskStack() == [TASK_INIT]
  }

  def "suspend after end_span"() {
    expect:
    tracker.startSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    tracker.endSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    !tracker.suspendTask()
  }

  def "out_of_band end_span with suspend_resume"() {
    expect:
    tracker.startSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    tracker.suspendTask()
    tracker.taskStack() == [MIGRATING, ACTIVE, TASK_INIT]
    tracker.endSpan()
    tracker.taskStack() == [MIGRATING, ACTIVE, TASK_INIT]
    tracker.resumeTask()
    tracker.taskStack() == [ACTIVE, ACTIVE, TASK_INIT]
    tracker.endTask()
    tracker.taskStack() == [TASK_INIT]
  }

  def "global resume_end_suspend"() {
    expect:
    tracker.startSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    tracker.suspendTask()
    tracker.taskStack() == [MIGRATING, ACTIVE, TASK_INIT]
    tracker.resumeTask()
    tracker.taskStack() == [ACTIVE, ACTIVE, TASK_INIT]
    tracker.endTask()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    tracker.suspendTask()
    tracker.taskStack() == [MIGRATING, ACTIVE, TASK_INIT]
  }

  def "resume after end_span"() {
    expect:
    tracker.startSpan()
    tracker.taskStack() == [ACTIVE, TASK_INIT]
    tracker.suspendTask()
    tracker.taskStack() == [MIGRATING, ACTIVE, TASK_INIT]
    tracker.endSpan()
    tracker.taskStack() == [MIGRATING, ACTIVE, TASK_INIT]
    tracker.resumeTask()
    tracker.taskStack() == [ACTIVE, ACTIVE, TASK_INIT]
    tracker.endTask()
    tracker.taskStack() == [TASK_INIT]
  }
}
