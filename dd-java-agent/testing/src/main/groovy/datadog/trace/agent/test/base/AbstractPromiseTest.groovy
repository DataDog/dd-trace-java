package datadog.trace.agent.test.base

import datadog.trace.agent.test.AgentTestRunner

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

abstract class AbstractPromiseTest<P> extends AgentTestRunner {

  abstract P newPromise()

  abstract void onComplete(P promise, Runnable callback)

  abstract void complete(P promise)

  def "test call with parent"() {
    setup:
    def promise = newPromise()

    when:
    runUnderTrace("parent") {
      onComplete(promise) {
        runUnderTrace("callback") {}
      }
      runUnderTrace("other") {
        complete(promise)
        blockUntilChildSpansFinished(1)
      }
    }

    then:
    assertTraces(1) {
      trace(0, 3) {
        basicSpan(it, 0, "parent")
        basicSpan(it, 1, "other", it.span(0))
        basicSpan(it, 2, "callback", it.span(0))
      }
    }
  }

  def "test call with parent delayed complete"() {
    setup:
    def promise = newPromise()

    when:
    runUnderTrace("parent") {
      onComplete(promise) {
        runUnderTrace("callback") {}
      }
    }

    runUnderTrace("other") {
      complete(promise)
    }

    then:
    assertTraces(2) {
      trace(0, 1) {
        basicSpan(it, 0, "other")
      }
      trace(1, 2) {
        basicSpan(it, 0, "callback", it.span(1))
        basicSpan(it, 1, "parent")
      }
    }
  }

  def "test call with parent complete separate thread"() {
    setup:
    final promise = newPromise()

    when:
    runUnderTrace("parent") {
      onComplete(promise) {
        runUnderTrace("callback") {}
      }
      Thread.start {
        complete(promise)
      }.join()
      blockUntilChildSpansFinished(1)
    }

    then:
    assertTraces(1) {
      trace(0, 2) {
        basicSpan(it, 0, "parent")
        basicSpan(it, 1, "callback", it.span(0))
      }
    }
  }

  def "test call with no parent"() {
    setup:
    def promise = newPromise()

    when:
    onComplete(promise) {
      runUnderTrace("callback") {}
    }

    runUnderTrace("other") {
      complete(promise)
      blockUntilChildSpansFinished(1)
    }

    then:
    assertTraces(1) {
      trace(0, 2) {
        // TODO: is this really the behavior we want?
        basicSpan(it, 0, "other")
        basicSpan(it, 1, "callback", it.span(0))
      }
    }
  }
}
