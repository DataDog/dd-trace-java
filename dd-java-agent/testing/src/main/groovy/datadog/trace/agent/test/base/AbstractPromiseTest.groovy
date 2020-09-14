package datadog.trace.agent.test.base


import datadog.trace.agent.test.AgentTestRunner

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
    def latch = new CountDownLatch(1)

    when:
    runUnderTrace("parent") {
      def mapped = map(promise) {
        runUnderTrace("mapped") {}
        "$it"
      }
      onComplete(mapped) {
        assert it == "$value"
        runUnderTrace("callback") {}
        latch.countDown()
      }
      runUnderTrace("other") {
        complete(promise, value)
        // This is here to sort the spans so that `mapped` always finishes first
        waitForLatchOrFail(latch)
      }
    }

    then:
    get(promise) == value
    assertTraces(1) {
      trace(4) {
        basicSpan(it,"parent")
        basicSpan(it, "other", it.span(0))
        basicSpan(it, "callback", it.span(0))
        basicSpan(it, "mapped", it.span(0))
      }
    }

    where:
    value << [true, false]
  }

  def "test call with parent delayed complete"() {
    setup:
    def promise = newPromise()
    def latch = new CountDownLatch(1)

    when:
    runUnderTrace("parent") {
      def mapped = map(promise) {
        runUnderTrace("mapped") {}
        "$it"
      }
      onComplete(mapped) {
        assert it == "$value"
        runUnderTrace("callback") {}
        latch.countDown()
      }
    }

    runUnderTrace("other") {
      complete(promise, value)
      // This is here to sort the traces so the `parent` always finishes first
      waitForLatchOrFail(latch)
    }

    then:
    get(promise) == value
    assertTraces(2) {
      trace(3) {
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
    def latch = new CountDownLatch(1)

    when:
    runUnderTrace("parent") {
      def mapped = map(promise) {
        runUnderTrace("mapped") {}
        "$it"
      }
      onComplete(mapped) {
        assert it == "$value"
        runUnderTrace("callback") {}
        latch.countDown()
      }
      Thread.start {
        complete(promise, value)
      }.join()
      // This is here to sort the spans so that `mapped` always finishes first
      waitForLatchOrFail(latch)
    }

    then:
    get(promise) == value
    assertTraces(1) {
      trace(3) {
        basicSpan(it, "parent")
        basicSpan(it, "callback", it.span(0))
        basicSpan(it, "mapped", it.span(0))
      }
    }

    where:
    value << [true, false]
  }

  def "test call with no parent"() {
    setup:
    def promise = newPromise()
    def latch = new CountDownLatch(1)

    when:
    def mapped = map(promise) {
      runUnderTrace("mapped") {}
      "$it"
    }
    onComplete(mapped) {
      assert it == "$value"
      runUnderTrace("callback") {}
      latch.countDown()
    }

    runUnderTrace("other") {
      complete(promise, value)
      // This is here to sort the spans so that `mapped` always finishes first
      waitForLatchOrFail(latch)
    }

    then:
    get(promise) == value
    assertTraces(1) {
      trace(3) {
        // TODO: is this really the behavior we want?
        basicSpan(it, "other")
        basicSpan(it, "callback", it.span(0))
        basicSpan(it, "mapped", it.span(0))
      }
    }

    where:
    value << [true, false]
  }

  void waitForLatchOrFail(CountDownLatch latch) {
    if (!latch.await(10, TimeUnit.SECONDS)) {
      throw new TimeoutException("Timed out waiting for latch for 10 seconds")
    }
  }
}
