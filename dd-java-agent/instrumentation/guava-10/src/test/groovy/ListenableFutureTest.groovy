import com.google.common.util.concurrent.SettableFuture
import datadog.trace.agent.test.base.AbstractPromiseTest
import spock.lang.Shared

import java.util.concurrent.Executors

class ListenableFutureTest extends AbstractPromiseTest<SettableFuture<Boolean>> {
  @Shared
  def executor = Executors.newFixedThreadPool(1)

  @Override
  SettableFuture<Boolean> newPromise() {
    return SettableFuture.create()
  }

  @Override
  void onComplete(SettableFuture<Boolean> promise, Runnable callback) {
    promise.addListener(callback, executor)
  }

  @Override
  void complete(SettableFuture<Boolean> promise) {
    promise.set(true)
  }
}
