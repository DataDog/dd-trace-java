import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import java.util.function.Function

class CompletableFuturePromiseNoAsyncTest extends CompletableFuturePromiseTest {

  @Override
  Executor executor() {
    return null
  }

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

//  @Override
//  boolean picksUpCompletingScope() {
//    return false
//  }
//
//  def "test call with no parent"() {
//    setup:
//    def promise = newPromise()
//    def latch = new CountDownLatch(1)
//
//    when:
//    def mapped = map(promise) {
//      runUnderTrace("mapped") {}
//      "$it"
//    }
//    onComplete(mapped) {
//      assert it == "$value"
//      runUnderTrace("callback") {}
//      latch.countDown()
//    }
//
//    runUnderTrace("other") {
//      complete(promise, value)
//      // This is here to sort the spans so that `mapped` always finishes first
//      waitForLatchOrFail(latch)
//    }
//
//    then:
//    get(promise) == value
//    assertTraces(2) {
//      trace(2) {
//        basicSpan(it, "other")
//        basicSpan(it, "mapped", it.span(0))
//      }
//      trace(1) {
//        basicSpan(it, "callback")
//      }
//    }
//
//    where:
//    value << [true, false]
//  }
//
}
