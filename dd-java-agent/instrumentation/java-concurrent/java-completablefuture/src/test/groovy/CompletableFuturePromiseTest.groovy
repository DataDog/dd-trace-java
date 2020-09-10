import datadog.trace.agent.test.base.AbstractPromiseTest
import spock.lang.Shared

import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.function.Function

class CompletableFuturePromiseTest extends AbstractPromiseTest<CompletableFuture<Boolean>, CompletableFuture<String>> {
  @Shared
  def executor = Executors.newFixedThreadPool(3) // Three is the magic number

  @Override
  CompletableFuture<Boolean> newPromise() {
    return new CompletableFuture<Boolean>()
  }

  @Override
  CompletableFuture<String> map(CompletableFuture<Boolean> promise, Closure<String> callback) {
    return promise.thenApplyAsync(new Function<Boolean, String>() {
      @Override
      String apply(Boolean value) {
        return callback.call(value)
      }
    }, executor).toCompletableFuture()
  }

  @Override
  void onComplete(CompletableFuture<String> promise, Closure callback) {
    promise.thenApplyAsync(callback, executor)
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
