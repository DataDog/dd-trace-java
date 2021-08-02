package checkpoints

import datadog.trace.agent.test.SpockRunner
import datadog.trace.agent.test.checkpoints.SuspendResumeValidator
import org.junit.runner.RunWith
import spock.lang.Specification

@RunWith(SpockRunner)
class SuspendResumeValidatorTest extends Specification {
  def tracker

  void setup() {
    tracker = new SuspendResumeValidator()
  }

  def "suspend with no start-span"() {
    expect:
    !tracker.suspendSpan()
  }

  def "resume with no start-span"() {
    expect:
    !tracker.resumeSpan()
  }

  def "suspend from start-span"() {
    expect:
    tracker.startSpan()
    tracker.suspendSpan()
    tracker.suspendSpan()
  }

  def "suspend after resume"() {
    expect:
    tracker.startSpan()
    tracker.suspendSpan()
    tracker.resumeSpan()
    tracker.suspendSpan()
    tracker.suspendSpan()
    tracker.resumeSpan()
    tracker.resumeSpan()
    tracker.endTask()
    tracker.endTask()
    tracker.endSpan()
    tracker.endSequence()
  }

  def "resume with no suspend"() {
    expect:
    tracker.startSpan()
    !tracker.resumeSpan()
  }

  def "fork_join"() {
    expect:
    tracker.startSpan()
    tracker.suspendSpan()
    tracker.suspendSpan()
    tracker.resumeSpan()
    tracker.endTask()
    tracker.resumeSpan()
    tracker.suspendSpan()
    tracker.resumeSpan()
    tracker.suspendSpan()
    tracker.endTask()
    tracker.endTask()
    tracker.resumeSpan()
    tracker.endTask()
    tracker.endSpan()
    tracker.endSequence()
  }

  def "simple suspend_resume sequence"() {
    expect:
    tracker.startSpan()
    tracker.suspendSpan()
    tracker.resumeSpan()
    tracker.endTask()
    tracker.endSpan()
    tracker.endSequence()
  }

  def "recursive suspend_resume sequence"() {
    expect:
    tracker.startSpan()
    tracker.suspendSpan()
    tracker.suspendSpan()
    tracker.suspendSpan()
    tracker.resumeSpan()
    tracker.resumeSpan()
    tracker.endTask()
    tracker.resumeSpan()
    tracker.endTask()
    tracker.endTask()
    tracker.endSpan()
    tracker.endSequence()
  }

  def "wrapping suspend_resume sequence"() {
    tracker.startSpan()
    tracker.suspendSpan()
    tracker.suspendSpan()
    tracker.resumeSpan()
    tracker.resumeSpan()
    tracker.endTask()
    tracker.endTask()
    tracker.endSpan()
    tracker.endSequence()
  }

  def "out_of_band end_span"() {
    expect:
    tracker.startSpan()
    tracker.endSpan()
    tracker.endTask()
    tracker.endSequence()
  }

  def "suspend after end_span"() {
    expect:
    tracker.startSpan()
    tracker.endSpan()
    tracker.suspendSpan()
    tracker.resumeSpan()
    tracker.endTask()
    tracker.endSequence()
  }

  def "out_of_band end_span with suspend_resume"() {
    expect:
    tracker.startSpan()
    tracker.suspendSpan()
    tracker.endSpan()
    tracker.resumeSpan()
    tracker.endTask()
    tracker.endSequence()
  }

  def "global resume_end_suspend"() {
    expect:
    tracker.startSpan()
    tracker.suspendSpan()
    tracker.resumeSpan()
    tracker.endTask()
    tracker.suspendSpan()
    tracker.resumeSpan()
    tracker.endSpan()
    tracker.endSequence()
  }

  def "resume after end_span"() {
    expect:
    tracker.startSpan()
    tracker.suspendSpan()
    tracker.endSpan()
    tracker.resumeSpan()
    tracker.endTask()
    tracker.endSequence()
  }

  def "suspend surplus"() {
    expect:
    tracker.startSpan()
    tracker.suspendSpan()
    tracker.suspendSpan()
    tracker.resumeSpan()
    tracker.endSpan()
    !tracker.endSequence()
  }

  def "resume surplus"() {
    expect:
    tracker.startSpan()
    tracker.suspendSpan()
    tracker.resumeSpan()
    !tracker.resumeSpan()
    tracker.endSpan()
    !tracker.endSequence()
  }
}
