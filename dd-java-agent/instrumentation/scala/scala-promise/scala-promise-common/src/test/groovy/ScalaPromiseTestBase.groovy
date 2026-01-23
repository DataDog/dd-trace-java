import datadog.trace.agent.test.base.AbstractPromiseTest
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.setAsyncPropagationEnabled

abstract class ScalaPromiseTestBase extends AbstractPromiseTest<Promise<Boolean>, Future<String>> {

  @Shared
  PromiseUtils promiseUtils = new PromiseUtils(getExecutionContext())

  abstract protected ExecutionContext getExecutionContext()

  @Override
  Promise<Boolean> newPromise() {
    return promiseUtils.newPromise()
  }

  @Override
  Future<String> map(Promise<Boolean> promise, Closure<String> callback) {
    return promiseUtils.map(promise, callback) as Future<String>
  }

  @Override
  void onComplete(Future<String> promise, Closure callback) {
    promiseUtils.onComplete(promise, callback)
  }

  @Override
  void complete(Promise<Boolean> promise, boolean value) {
    promise.success(value)
  }

  @Override
  boolean get(Promise<Boolean> promise) {
    return promise.future().value().get().get()
  }

  @Override
  boolean picksUpCompletingScope() {
    return true
  }

  def "doesn't propagate non async completing scope"() {
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
      setAsyncPropagationEnabled(false)
      complete(promise, value)
    }

    then:
    get(promise) == value
    assertTraces(3) {
      trace(1,) {
        basicSpan(it, "other")
      }
      trace(1,) {
        basicSpan(it, "mapped")
      }
      trace(1, true) {
        basicSpan(it, "callback")
      }
    }

    where:
    value << [true, false]
  }

  def "picks correct span when completing promise"() {
    setup:
    def promise = newPromise()
    def otherPromise = newPromise()

    when:
    runUnderTrace("parent") {
      map(promise) {
        boolean b = it
        runUnderTrace("mapped") {
          complete(otherPromise, !b)
        }
        "$it"
      }
      def mappedOther = map(otherPromise) {
        runUnderTrace("mappedOther") {}
        "$it"
      }
      onComplete(mappedOther) {
        assert it != "$value"
        runUnderTrace("callback") {}
      }

      runUnderTrace("other") {
        complete(promise, value)
      }
    }

    then:
    get(promise) == value
    promiseUtils.await(otherPromise.future()) != value
    assertTraces(1) {
      def pIndex = completingScopePriority() ? 3 : 4
      def opIndex = completingScopePriority() ? 1 : 4
      trace(5, true) {
        basicSpan(it, "callback", it.span(opIndex))
        basicSpan(it, "mapped", it.span(pIndex))
        basicSpan(it, "mappedOther", it.span(opIndex))
        basicSpan(it, "other", it.span(4))
        basicSpan(it, "parent")
      }
    }

    where:
    value << [true, false]
  }

  def "picks correct span when completing promise with future"() {
    setup:
    def promise = newPromise()
    def otherPromise = newPromise()

    when:
    runUnderTrace("parent") {
      map(promise) {
        runUnderTrace("mapped") {
          promiseUtils.completeWith(otherPromise, promise.future())
        }
        "$it"
      }
      def mappedOther = map(otherPromise) {
        runUnderTrace("mappedOther") {}
        "$it"
      }
      onComplete(mappedOther) {
        assert it == "$value"
        runUnderTrace("callback") {}
      }

      runUnderTrace("other") {
        complete(promise, value)
      }
    }

    then:
    get(promise) == value
    promiseUtils.await(otherPromise.future()) == value
    assertTraces(1) {
      def pIndex = completingScopePriority() ? 3 : 4
      trace(5, true) {
        basicSpan(it, "callback", it.span(pIndex))
        basicSpan(it, "mapped", it.span(pIndex))
        basicSpan(it, "mappedOther", it.span(pIndex))
        basicSpan(it, "other", it.span(4))
        basicSpan(it, "parent")
      }
    }

    where:
    value << [true, false]
  }
}

abstract class ScalaPromiseCompletionPriorityTestBase extends ScalaPromiseTestBase {
  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.trace.integration.scala_promise_completion_priority.enabled", "true")
  }

  @Override
  boolean completingScopePriority() {
    return true
  }

  String await(Future<String>future) {
    return promiseUtils.await(future)
  }

  def "reuses span from completed promise"() {
    setup:
    def promise = newPromise()

    when:
    def otherSpan = runUnderTrace("other") {
      complete(promise, value)
      activeSpan()
    }
    def mapped = map(promise) {
      runUnderTrace("mapped") {}
      "$it"
    }

    then:
    get(promise) == value
    await(mapped) == "$value"
    assertTraces(2) {
      trace(1,) {
        basicSpan(it, "other")
      }
      trace(1) {
        basicSpan(it, "mapped", otherSpan)
      }
    }

    when:
    TEST_WRITER.clear()
    onComplete(mapped) {
      assert it == "$value"
      runUnderTrace("callback") {}
    }

    then:
    assertTraces(1) {
      trace(1) {
        basicSpan(it, "callback", otherSpan)
      }
    }

    where:
    value << [true, false]
  }
}
