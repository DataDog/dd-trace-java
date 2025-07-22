import datadog.trace.agent.test.base.AbstractPromiseTest
import io.netty.util.concurrent.DefaultEventExecutorGroup
import io.netty.util.concurrent.GenericProgressiveFutureListener
import io.netty.util.concurrent.ProgressiveFuture
import io.netty.util.concurrent.ProgressivePromise
import spock.lang.Shared

import static datadog.trace.agent.test.utils.TraceUtils.basicSpan
import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class NettyProgressivePromiseTest extends AbstractPromiseTest<ProgressivePromise<Boolean>, ProgressivePromise<String>> {

  @Override
  void configurePreAgent() {
    super.configurePreAgent()

    injectSysConfig("dd.integration.netty-promise.enabled", "true")
  }

  @Shared
  def executor = new DefaultEventExecutorGroup(1).next()

  @Override
  ProgressivePromise<Boolean> newPromise() {
    return executor.newProgressivePromise()
  }

  @Override
  ProgressivePromise<String> map(ProgressivePromise<Boolean> promise, Closure<String> callback) {
    ProgressivePromise<String> mapped = executor.newProgressivePromise()
    promise.addListener {
      mapped.setSuccess(it.getNow().toString())
    }
    mapped.addListener {
      callback(it.getNow())
    }
    return mapped
  }

  @Override
  void onComplete(ProgressivePromise<String> promise, Closure callback) {
    promise.addListener {
      callback(it.getNow())
    }
  }

  @Override
  void complete(ProgressivePromise<Boolean> promise, boolean value) {
    promise.setSuccess(value)
  }

  @Override
  boolean get(ProgressivePromise<Boolean> promise) {
    return promise.get()
  }

  def "test progressive addListeners"() {
    setup:
    def promise = newPromise()

    when:
    runUnderTrace("parent") {
      def listeners = iterations.collect { int i ->
        return new GenericProgressiveFutureListener<ProgressiveFuture<?>>() {
            void operationComplete(ProgressiveFuture<?> future) throws Exception {
              runUnderTrace("listen$i") {}
            }

            void operationProgressed(ProgressiveFuture<?> future, long progress, long total) throws Exception {
              runUnderTrace("progress$i") {}
            }
          }
      }
      promise.addListeners(listeners.toArray(new GenericProgressiveFutureListener[0]))
    }

    then:
    promise.setProgress(50, 100)

    when:
    complete(promise, value)

    then:
    get(promise) == value
    assertTraces(1) {
      trace(count * 2 + 1, true) { trace ->
        sortSpansByStart()
        basicSpan(trace, "parent")
        iterations.each {
          basicSpan(trace, "progress$it", trace.span(0))
        }
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
