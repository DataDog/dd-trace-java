import datadog.trace.agent.test.base.AbstractPromiseTest
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import spock.lang.Shared

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
}
