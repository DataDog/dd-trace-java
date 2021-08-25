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
    tracker.startSpan(1L)
    tracker.endSpan(1L)
    tracker.endSequence()
  }

  def "end span on other thread"() {
    expect:
    tracker.startSpan(1L)
    tracker.startSpan(2L)
    tracker.suspendSpan(2L)
    tracker.endSpan(1L)
    tracker.endSequence()
  }

  def "overlapping spans"() {
    expect:
    tracker.startSpan(1L)
    tracker.startSpan(2L)
    !tracker.endSpan(1L)
    !tracker.endSpan(2L)
  }

  def "overlapping spans complex"() {
    expect:
    tracker.startSpan(1L)
    tracker.startSpan(2L)
    tracker.startSpan(3)
    tracker.endSpan(3)
    !tracker.endSpan(1L)
    !tracker.endSpan(2L)
  }

  def "suspend resume same thread"() {
    expect:
    tracker.startSpan(1L)
    tracker.suspendSpan(1L)
    tracker.suspendSpan(1L)
    tracker.endTask(1L)
    tracker.resumeSpan(1L)
    tracker.resumeSpan(1L)
    tracker.endTask(1L)
    tracker.endSpan(1L)
    tracker.endSequence()
  }

  def "end task after end span"() {
    expect:
    tracker.resumeSpan(1L)
    tracker.endSpan(1L)
    tracker.endTask(1L)
    tracker.endSequence()
  }

  def "end task before end span"() {
    expect:
    tracker.resumeSpan(1L)
    tracker.endTask(1L)
    tracker.endSpan(1L)
    tracker.endSequence()
  }

  def "included spans"() {
    expect:
    tracker.startSpan(1L)
    tracker.suspendSpan(1L)
    tracker.startSpan(2L)
    tracker.endSpan(2L)
    tracker.startSpan(3)
    tracker.endSpan(3)
    tracker.resumeSpan(1L)
    tracker.endSpan(1L)
    tracker.endSequence()
  }

  def "dangling spans"() {
    expect:
    tracker.startSpan(1L)
    tracker.startSpan(2L)
    !tracker.endSequence()
  }

  def "dangling suspends"() {
    expect:
    tracker.startSpan(1L)
    tracker.suspendSpan(1L)
    tracker.endSequence()
  }

  def "dangling suspend after end span"() {
    expect:
    tracker.startSpan(1L)
    tracker.endSpan(1L)
    tracker.suspendSpan(1L)
    tracker.endSequence()
  }

  def "interleaving with suspend-resume (T1)"() {
    // this sequence involves two threads T1 and T2
    expect:
    tracker.startSpan(1L)          // T1
    tracker.suspendSpan(1L)        // T1
    // tracker.resumeSpan(1L)      // T2
    // tracker.startSpan(2L)       // T2
    // tracker.suspendSpan(2L)     // T2
    tracker.resumeSpan(2L)         // T1
    tracker.endTask(2L)            // T1
    // tracker.endSpan(2L)         // T2
    tracker.endSpan(1L)            // T1
  }

  def "interleaving with suspend-resume (T2)"() {
    // this sequence involves two threads T1 and T2
    expect:
    // tracker.startSpan(1L)       // T1
    // tracker.suspendSpan(1L)     // T1
    tracker.resumeSpan(1L)         // T2
    tracker.startSpan(2L)          // T2
    tracker.suspendSpan(2L)        // T2
    // tracker.resumeSpan(2L)      // T1
    // tracker.endTask(2L)         // T1
    tracker.endSpan(2L)            // T2
    // tracker.endSpan(1L)         // T1
  }
}
