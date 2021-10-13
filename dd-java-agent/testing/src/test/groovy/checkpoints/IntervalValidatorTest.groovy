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

  def "start_end span"() {
    expect:
    tracker.startSpan(1L)
    tracker.endSpan(1L)
    tracker.endSequence()
  }

  def "start endtask endspan"() {
    expect:
    tracker.startSpan(1L)
    tracker.suspendSpan(1L)
    tracker.resumeSpan(1L)
    tracker.endTask(1L)
    tracker.resumeSpan(1L)
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

  def "interleaving intervals with suspend on the same thread"() {
    expect:
    tracker.startSpan(1L)
    tracker.suspendSpan(1L)
    tracker.startSpan(2L)
    tracker.suspendSpan(2L)
    tracker.resumeSpan(1L)
    tracker.endTask(1L)
    tracker.resumeSpan(1L)
    tracker.endSpan(1L)
    tracker.resumeSpan(2L)
    tracker.endSpan(2L)
  }

  def "weird sequence"() {
    expect:
    tracker.resumeSpan(1L)
    tracker.endTask(1L)
    tracker.resumeSpan(1L)
    tracker.startSpan(3L)
    tracker.resumeSpan(1L)
    tracker.suspendSpan(1L)
    tracker.resumeSpan(1L)
    tracker.suspendSpan(1L)
    tracker.suspendSpan(1L)
    tracker.endSpan(3L)
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
