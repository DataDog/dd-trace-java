package datadog.trace.agent.test.base

import datadog.trace.agent.test.InstrumentationSpecification
import spock.lang.IgnoreIf

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

// TODO: add a test for a longer chain of promises
abstract class AbstractPromiseTest<P, M> extends InstrumentationSpecification {

  abstract P newPromise()

  abstract M map(P promise, Closure<String> callback)

  abstract void onComplete(M promise, Closure callback)

  abstract void complete(P promise, boolean value)

  abstract boolean get(P promise)

  // Does this instrumentation pick up the completing scope?
  // That is e.g. not how it was decided that CompletableFuture should work
  boolean picksUpCompletingScope() {
    true
  }

  // Does the completing scope have priority over the scopes captured while adding operations?
  boolean completingScopePriority() {
    false
  }

  def "test call with parent"() {
    setup:
    def promise = newPromise()

    when:
    runUnderTrace("parent") {
      def mapped = map(promise) {
        runUnderTrace("mapped") {}
        "$it"
      }
      onComplete(mapped) {
        assert it == "$value"
        runUnderTrace("callback") {}
      }
      runUnderTrace("other") {
        complete(promise, value)
      }
    }

    then:
    get(promise) == value
    assertTraces(1) {
      def pIndex = completingScopePriority() ? 2 : 3
      trace(4, true) {
        basicSpan(it, "callback", it.span(pIndex))
        basicSpan(it, "mapped", it.span(pIndex))
        basicSpan(it, "other", it.span(3))
        basicSpan(it, "parent")
      }
    }

    where:
    value << [true, false]
  }

  def "test call with parent delayed complete"() {
    setup:
    def promise = newPromise()

    when:
    runUnderTrace("parent") {
      def mapped = map(promise) {
        runUnderTrace("mapped") {}
        "$it"
      }
      onComplete(mapped) {
        assert it == "$value"
        runUnderTrace("callback") {}
      }
    }

    runUnderTrace("other") {
      complete(promise, value)
    }

    then:
    get(promise) == value
    assertTraces(2) {
      if (!completingScopePriority()) {
        trace(3, true) {
          basicSpan(it, "callback", it.span(2))
          basicSpan(it, "mapped", it.span(2))
          basicSpan(it, "parent")
        }
        trace(1) {
          basicSpan(it, "other")
        }
      } else {
        trace(1) {
          basicSpan(it, "parent")
        }
        trace(3, true) {
          basicSpan(it, "callback", it.span(2))
          basicSpan(it, "mapped", it.span(2))
          basicSpan(it, "other")
        }
      }
    }

    where:
    value << [true, false]
  }

  def "test call with parent complete separate thread"() {
    setup:
    final promise = newPromise()

    when:
    runUnderTrace("parent") {
      def mapped = map(promise) {
        runUnderTrace("mapped") {}
        "$it"
      }
      onComplete(mapped) {
        assert it == "$value"
        runUnderTrace("callback") {}
      }
      Thread.start {
        complete(promise, value)
      }.join()
    }

    then:
    get(promise) == value
    assertTraces(1) {
      trace(3, true) {
        basicSpan(it, "callback", it.span(2))
        basicSpan(it, "mapped", it.span(2))
        basicSpan(it, "parent")
      }
    }

    where:
    value << [true, false]
  }

  @IgnoreIf({ !instance.picksUpCompletingScope() })
  def "test call with no parent (completing scope)"() {
    setup:
    def promise = newPromise()

    when:
    def mapped = map(promise) {
      runUnderTrace("mapped") {}
      "$it"
    }
    onComplete(mapped) {
      assert it == "$value"
      runUnderTrace("callback") {}
    }

    runUnderTrace("other") {
      complete(promise, value)
    }

    then:
    get(promise) == value
    assertTraces(1) {
      trace(3, true) {
        // TODO: is this really the behavior we want?
        basicSpan(it, "callback", it.span(2))
        basicSpan(it, "mapped", it.span(2))
        basicSpan(it, "other")
      }
    }

    where:
    value << [true, false]
  }
}
