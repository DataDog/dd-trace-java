import datadog.trace.agent.test.base.AbstractPromiseTest

import java.util.concurrent.CompletableFuture
import java.util.function.Function

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
  Boolean get(CompletableFuture<Boolean> promise) {
    return promise.get()
  }
}
