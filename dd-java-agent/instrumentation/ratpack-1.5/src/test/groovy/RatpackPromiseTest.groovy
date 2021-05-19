import datadog.trace.agent.test.base.AbstractPromiseTest
import ratpack.exec.Blocking
import ratpack.exec.Promise
import ratpack.exec.util.Promised
import ratpack.test.exec.ExecHarness
import spock.lang.AutoCleanup
import spock.lang.Shared

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope

class RatpackPromiseTest extends AbstractPromiseTest<Promised<Boolean>, Promise<String>> {

  @AutoCleanup
  @Shared
  ExecHarness exec = ExecHarness.harness(1)

  @Override
  boolean picksUpCompletingScope() {
    return false
  }

  @Override
  Promised<Boolean> newPromise() {
    return new Promised<Boolean>()
  }

  @Override
  Promise<String> map(Promised<Boolean> promise, Closure<String> callback) {
    return promise.promise().map {
      callback(it)
    }
  }

  @Override
  void onComplete(Promise<String> promise, Closure callback) {
    // capture continuation to ensure parent isn't reported independently since this is an async operation.
    def continuation = activeScope()?.capture()
    exec.fork().start {
      promise.then {
        callback(it)
        continuation?.cancel()
      }
    }
  }

  @Override
  void complete(Promised<Boolean> promise, boolean value) {
    promise.success(value)
  }

  @Override
  boolean get(Promised<Boolean> promise) {
    return exec.yield({
      Blocking.get {
        Blocking.on(promise.promise())
      }
    }).valueOrThrow
  }
}
