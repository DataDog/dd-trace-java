package datadog.trace.agent.test.base


import datadog.trace.agent.test.AgentTestRunner

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

// TODO: add a test for a longer chain of promises
abstract class AbstractPromiseTest<P, M> extends AgentTestRunner {

  abstract P newPromise()

  abstract M map(P promise, Closure<String> callback)

  abstract void onComplete(M promise, Closure callback)

  abstract void complete(P promise, boolean value)

  abstract Boolean get(P promise)

  def "test call with parent"() {
    setup:
    def promise = newPromise()

    when:
    runUnderTrace("parent") {
      def mapped = map(promise) { "$it" }
      onComplete(mapped) {
        assert it == "$value"
        runUnderTrace("callback") {}
      }
      runUnderTrace("other") {
        complete(promise, value)
        blockUntilChildSpansFinished(1)
      }
    }

    then:
    get(promise) == value
    assertTraces(1) {
      trace(3) {
        basicSpan(it, "parent")
        basicSpan(it, "other", it.span(0))
        basicSpan(it, "callback", it.span(0))
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
      def mapped = map(promise) { "$it" }
      onComplete(mapped) {
        assert it == "$value"
        runUnderTrace("callback") {}
      }
    }

    runUnderTrace("other") {
      complete(promise, value)
      TEST_WRITER.waitForTraces(1)
    }

    then:
    get(promise) == value
    assertTraces(2) {
      trace(2) {
        basicSpan(it, "callback", it.span(1))
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
      def mapped = map(promise) { "$it" }
      onComplete(mapped) {
        assert it == "$value"
        runUnderTrace("callback") {}
      }
      Thread.start {
        complete(promise, value)
      }.join()
      blockUntilChildSpansFinished(1)
    }

    then:
    get(promise) == value
    assertTraces(1) {
      trace(2) {
        basicSpan(it, "parent")
        basicSpan(it, "callback", it.span(0))
      }
    }

    where:
    value << [true, false]
  }

  def "test call with no parent"() {
    setup:
    def promise = newPromise()

    when:
    def mapped = map(promise) { "$it" }
    onComplete(mapped) {
      assert it == "$value"
      runUnderTrace("callback") {}
    }

    runUnderTrace("other") {
      complete(promise, value)
      blockUntilChildSpansFinished(1)
    }

    then:
    get(promise) == value
    assertTraces(1) {
      trace(2) {
        // TODO: is this really the behavior we want?
        basicSpan(it, "other")
        basicSpan(it, "callback", it.span(0))
      }
    }

    where:
    value << [true, false]
  }
}
