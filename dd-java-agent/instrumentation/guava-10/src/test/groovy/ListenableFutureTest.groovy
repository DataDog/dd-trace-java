import com.google.common.base.Function
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import datadog.trace.agent.test.base.AbstractPromiseTest
import spock.lang.Shared

import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class ListenableFutureTest extends AbstractPromiseTest<SettableFuture<Boolean>, ListenableFuture<String>> {
  @Shared
  ExecutorService exHolder = null

  def executor() {
    // We need lazy init, or else the constructor is run before the instrumentation is applied
    return exHolder == null ? exHolder = Executors.newFixedThreadPool(1) : exHolder
  }

  @Override
  def cleanupSpec() {
    executor().shutdownNow()
  }

  @Override
  SettableFuture<Boolean> newPromise() {
    return SettableFuture.create()
  }

  @Override
  ListenableFuture<String> map(SettableFuture<Boolean> promise, Closure<String> callback) {
    return Futures.transform(promise, (Function) callback, executor())
  }

  @Override
  void onComplete(ListenableFuture<String> promise, Closure callback) {
    promise.addListener({ -> callback(promise.get()) }, executor())
  }


  @Override
  void complete(SettableFuture<Boolean> promise, boolean value) {
    promise.set(value)
  }

  @Override
  boolean get(SettableFuture<Boolean> promise) {
    return promise.get()
  }
}
