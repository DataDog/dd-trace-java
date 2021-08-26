import datadog.trace.agent.test.base.AbstractPromiseTest

import java.util.concurrent.CompletableFuture
import java.util.function.Function

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class CompletableFuturePromiseNoAsyncTest extends AbstractPromiseTest<CompletableFuture<Boolean>, CompletableFuture<String>> {
  @Override
  CompletableFuture<Boolean> newPromise() {
    return new CompletableFuture<Boolean>()
  }

  @Override
  CompletableFuture<String> map(CompletableFuture<Boolean> promise, Closure<String> callback) {
    return promise.thenApply(new Function<Boolean, String>() {
        @Override
        String apply(Boolean value) {
          return callback.call(value)
        }
      }).toCompletableFuture()
  }

  @Override
  void onComplete(CompletableFuture<String> promise, Closure callback) {
    promise.thenApplyAsync(callback)
  }

  @Override
  void complete(CompletableFuture<Boolean> promise, boolean value) {
    promise.complete(value)
  }

  @Override
  boolean get(CompletableFuture<Boolean> promise) {
    return promise.get()
  }

  @Override
  boolean picksUpCompletingScope() {
    return false
  }

  def "test call with no parent"() {
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
    assertTraces(2) {
      trace(2, true) {
        basicSpan(it, "mapped", it.span(1))
        basicSpan(it, "other")
      }
      trace(1) {
        basicSpan(it, "callback")
      }
    }

    where:
    value << [true, false]
  }
}
