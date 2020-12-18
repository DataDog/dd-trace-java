package datadog.trace.agent.test.base

import datadog.trace.agent.test.AgentTestRunner

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static org.junit.Assume.*

// TODO: add a test for a longer chain of promises
abstract class AbstractPromiseTest<P, M> extends AgentTestRunner {

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
      trace(4, true) {
        basicSpan(it, "callback", it.span(3))
        basicSpan(it, "mapped", it.span(3))
        basicSpan(it, "other", it.span(3))
        basicSpan(it,"parent")
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
      trace(3, true) {
        basicSpan(it, "callback", it.span(2))
        basicSpan(it, "mapped", it.span(2))
        basicSpan(it, "parent")
      }
      trace(1) {
        basicSpan(it, "other")
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

  def "test call with no parent (completing scope)"() {
    setup:
    assumeTrue(picksUpCompletingScope())
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
