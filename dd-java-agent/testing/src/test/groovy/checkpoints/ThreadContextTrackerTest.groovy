package checkpoints

import datadog.trace.agent.test.SpockRunner
import datadog.trace.agent.test.checkpoints.ThreadContextTracker
import org.junit.runner.RunWith
import spock.lang.Specification

@RunWith(SpockRunner)
class ThreadContextTrackerTest extends Specification {
  def tracker

  void setup() {
    tracker = new ThreadContextTracker()
  }

  def "start_end span"() {
    expect:
    tracker.startSpan(1)
    tracker.endSpan(1)
  }

  def "end span on other thread"() {
    expect:
    tracker.startSpan(1)
    tracker.startSpan(2)
    tracker.suspendTask(2)
    tracker.endSpan(1)
  }

  def "overlapping spans"() {
    expect:
    tracker.startSpan(1)
    tracker.startSpan(2)
    !tracker.endSpan(1)
    !tracker.endSpan(2)
  }

  def "overlapping spans complex"() {
    expect:
    tracker.startSpan(1)
    tracker.startSpan(2)
    tracker.startSpan(3)
    tracker.endSpan(3)
    !tracker.endSpan(1)
    !tracker.endSpan(2)
  }

  def "suspend resume same thread"() {
    expect:
    tracker.startSpan(1)
    tracker.suspendTask(1)
    tracker.suspendTask(1)
    tracker.endTask(1)
    tracker.resumeTask(1)
    tracker.resumeTask(1)
    tracker.endTask(1)
    tracker.endSpan(1)
  }

  def "end task after end span"() {
    expect:
    tracker.resumeTask(1)
    tracker.endSpan(1)
    tracker.endTask(1)
  }

  def "end task before end span"() {
    expect:
    tracker.resumeTask(1)
    tracker.endTask(1)
    tracker.endSpan(1)
  }

  def "included spans"() {
    expect:
    tracker.startSpan(1)
    tracker.suspendTask(1)
    tracker.startSpan(2)
    tracker.endSpan(2)
    tracker.startSpan(3)
    tracker.endSpan(3)
    tracker.endSpan(1)
  }
}
