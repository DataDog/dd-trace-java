import datadog.trace.agent.test.base.AbstractPromiseTest
import scala.concurrent.Future
import scala.concurrent.Promise

class Scala210PromiseTest extends AbstractPromiseTest<Promise<Boolean>, Future<String>> {
  @Override
  Promise<Boolean> newPromise() {
    return PromiseUtils.newPromise()
  }

  @Override
  Future<String> map(Promise<Boolean> promise, Closure<String> callback) {
    return PromiseUtils.map(promise, callback)
  }

  @Override
  void onComplete(Future<String> promise, Closure callback) {
    PromiseUtils.onComplete(promise, callback)
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
    return false
  }
}
