package checkpoints

import datadog.trace.agent.test.SpockRunner
import datadog.trace.agent.test.checkpoints.IntervalValidator
import org.junit.runner.RunWith
import spock.lang.Specification

@RunWith(SpockRunner)
class IntervalValidatorTest extends Specification {
  def tracker

  void setup() {
    tracker = new IntervalValidator()
  }

  def "start_end span"() {
    expect:
    tracker.startSpan(1)
    tracker.endSpan(1)
    tracker.endSequence()
  }

  def "end span on other thread"() {
    expect:
    tracker.startSpan(1)
    tracker.startSpan(2)
    tracker.suspendSpan(2)
    tracker.endSpan(1)
    tracker.endSequence()
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
    tracker.suspendSpan(1)
    tracker.suspendSpan(1)
    tracker.endTask(1)
    tracker.resumeSpan(1)
    tracker.resumeSpan(1)
    tracker.endTask(1)
    tracker.endSpan(1)
    tracker.endSequence()
  }

  def "end task after end span"() {
    expect:
    tracker.resumeSpan(1)
    tracker.endSpan(1)
    tracker.endTask(1)
    tracker.endSequence()
  }

  def "end task before end span"() {
    expect:
    tracker.resumeSpan(1)
    tracker.endTask(1)
    tracker.endSpan(1)
    tracker.endSequence()
  }

  def "included spans"() {
    expect:
    tracker.startSpan(1)
    tracker.suspendSpan(1)
    tracker.startSpan(2)
    tracker.endSpan(2)
    tracker.startSpan(3)
    tracker.endSpan(3)
    tracker.resumeSpan(1)
    tracker.endSpan(1)
    tracker.endSequence()
  }

  def "dangling spans"() {
    expect:
    tracker.startSpan(1)
    tracker.startSpan(2)
    !tracker.endSequence()
  }

  def "dangling suspends"() {
    expect:
    tracker.startSpan(1)
    tracker.suspendSpan(1)
    !tracker.endSequence()
  }
}
