import datadog.trace.agent.test.base.AbstractPromiseTest
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.Future
import io.netty.util.concurrent.GenericFutureListener
import io.netty.util.concurrent.Promise
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class NettyPromiseTest extends AbstractPromiseTest<Promise<Boolean>, Promise<String>> {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.netty-promise.enabled", "true")
  }

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

  def "test addListeners"() {
    setup:
    def promise = newPromise()

    when:
    runUnderTrace("parent") {
      def listeners = iterations.collect { int i ->
        return new GenericFutureListener<Future<?>>() {

            @Override
            void operationComplete(Future<?> future) throws Exception {
              runUnderTrace("listen$i") {}
            }
          }
      }
      promise.addListeners(listeners.toArray(new GenericFutureListener[0]))
    }
    complete(promise, value)

    then:
    get(promise) == value
    assertTraces(1) {
      trace(count + 1, true) { trace ->
        sortSpansByStart()
        basicSpan(trace, "parent")
        iterations.each {
          basicSpan(trace, "listen$it", trace.span(0))
        }
      }
    }

    where:
    value << [true, false]
    count = 2
    iterations = (1..count)
  }
}
