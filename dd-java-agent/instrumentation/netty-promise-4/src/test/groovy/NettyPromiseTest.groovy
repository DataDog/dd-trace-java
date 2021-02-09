import datadog.trace.agent.test.base.AbstractPromiseTest
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.Promise
import spock.lang.Shared

class NettyPromiseTest extends AbstractPromiseTest<Promise<Boolean>, Promise<String>> {
  @Shared
  def executor = new DefaultEventExecutorGroup(1).next()

  @Override
  Promise<Boolean> newPromise() {
    return executor.newPromise()
  }

  @Override
  Promise<String> map(Promise<Boolean> promise, Closure<String> callback) {
    Promise<String> mapped = executor.newPromise()
    promise.addListener {
      mapped.setSuccess(it.getNow().toString())
    }
    mapped.addListener {
      callback(it.getNow())
    }
    return mapped
  }

  @Override
  void onComplete(Promise<String> promise, Closure callback) {
    promise.addListener {
      callback(it.getNow())
    }
  }


  @Override
  void complete(Promise<Boolean> promise, boolean value) {
    promise.setSuccess(value)
  }

  @Override
  boolean get(Promise<Boolean> promise) {
    return promise.get()
  }
}
