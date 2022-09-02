package checkpoints

import datadog.trace.agent.test.SpockRunner
import datadog.trace.agent.test.checkpoints.AbstractValidator.Result
import datadog.trace.agent.test.checkpoints.IntervalValidator
import org.junit.runner.RunWith
import spock.lang.Specification

@RunWith(SpockRunner)
class IntervalValidatorTest extends Specification {
  def tracker

  void setup() {
    tracker = new IntervalValidator()
  }

  def "empty stack"() {
    expect:
    !tracker.endTask(1L)
  }

  def "single interval"() {
    expect:
    tracker.startTask(1L)
    tracker.endTask(1L)
    tracker.endSequence()
  }

  def "serial intervals"() {
    expect:
    tracker.startTask(1L)
    tracker.endTask(1L)
    tracker.startTask(2L)
    tracker.endTask(2L)
    tracker.endSequence()
  }

  def "nested intervals"() {
    expect:
    tracker.startTask(1L)
    tracker.startTask(2L)
    tracker.endTask(2L)
    tracker.endTask(1L)
    tracker.endSequence()
  }

  def "overlapping intervals"() {
    expect:
    tracker.startTask(1L)
    tracker.startTask(2L)
    !tracker.endTask(1L)
    !tracker.endTask(2L)
  }

  def "dangling interval simple"() {
    expect:
    tracker.startTask(1L)
    !tracker.endSequence()
  }

  def "dangling interval nested"() {
    expect:
    tracker.startTask(1L)
    tracker.startTask(2L)
    tracker.endTask(2L)
    !tracker.endSequence()
  }

  /*
   The following case is an example how one can use `replayThreadSequence` to debug the actual checkpoint sequence
   captured by a failing test
   */
  //  def "debug weird sequence"() {
  //    expect:
  //    replayThreadSequence("resume/62-|-resume/61-|-startSpan/64-|-resume/61-|-suspend/61-|-endSpan/64-|-endTask/61-|-resume/61-|--------------|-startSpan/66-|-resume/61-|-suspend/61-|-endSpan/66-|-endTask/61-|-resume/61-|-startSpan/67-|-----------|------------|------------|-endSpan/67-|--------------|-endTask/61-|-resume/61-|------------|-startSpan/69-|-resume/61-|------------|-suspend/61-|-endSpan/69-|-endTask/61-|-resume/61-|-startSpan/70-|-resume/61-|-suspend/61-|-endSpan/70-|-endTask/61-|-resume/61-|-startSpan/71-|-resume/61-|-suspend/61-|-endSpan/71-|-endTask/61-|-resume/61-|-startSpan/72-|-resume/61-|-suspend/61-|-endSpan/72-|-endTask/61-|-resume/61-|-startSpan/73-|-resume/61-|-suspend/61-|-endSpan/73-|-endTask/61-|-resume/61-|-startSpan/74-|-endSpan/74-|-endTask/61-|-endSpan/61-|-***endTask/62***-|")
  //  }

  def replayThreadSequence(String sequence) {
    for (def it : sequence.replace('-', '').replace('*', '').split("\\|")) {
      def parts = it.split("/")
      if (parts.length != 2) {
        continue
      }
      def id = Long.parseLong(parts[1])

      def rslt
      switch (parts[0]) {
        case "startSpan":
          rslt = tracker.startSpan(id)
          break
        case "suspend":
          rslt = tracker.suspendSpan(id)
          break
        case "resume":
          rslt = tracker.resumeSpan(id)
          break
        case "endSpan":
          rslt = tracker.endSpan(id)
          break
        case "endTask":
          rslt = tracker.endTask(id)
          break
      }
      if (!rslt.isValid()) {
        return rslt
      }
    }
    return Result.OK
  }
}
