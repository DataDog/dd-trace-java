import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.github.resilience4j.bulkhead.ThreadPoolBulkhead

import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.function.Supplier

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class ThreadPoolBulkheadTest extends InstrumentationSpecification {

  def "decorate callable with thread pool bulkhead"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.RESILIENCE4J_MEASURED_ENABLED, measuredEnabled.toString())
    injectSysConfig(TraceInstrumentationConfig.RESILIENCE4J_TAG_METRICS_ENABLED, tagMetricsEnabled.toString())

    def metrics = Mock(ThreadPoolBulkhead.Metrics)
    def bulkhead = Mock(ThreadPoolBulkhead)
    bulkhead.getName() >> "thread-pool-bulkhead-1"
    bulkhead.getMetrics() >> metrics
    metrics.getThreadPoolSize() >> 5
    metrics.getCoreThreadPoolSize() >> 3
    metrics.getMaximumThreadPoolSize() >> 10
    metrics.getRemainingQueueCapacity() >> 15

    when:
    Callable<String> callable = ThreadPoolBulkhead.decorateCallable(bulkhead) { serviceCall("callable-result") }

    then:
    // Execute in parent trace context
    def result
    runUnderTrace("parent") {
      result = callable.call()
    }
    result == "callable-result"

    then:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
          errored false
        }
        span(1) {
          operationName "resilience4j"
          childOf(span(0))
          errored false
          measured measuredEnabled
          tags {
            "$Tags.COMPONENT" "resilience4j"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_INTERNAL
            "resilience4j.bulkhead.name" "thread-pool-bulkhead-1"
            "resilience4j.bulkhead.type" "threadpool"
            if (tagMetricsEnabled) {
              "resilience4j.bulkhead.metrics.thread_pool_size" 5
              "resilience4j.bulkhead.metrics.core_thread_pool_size" 3
              "resilience4j.bulkhead.metrics.maximum_thread_pool_size" 10
              "resilience4j.bulkhead.metrics.remaining_queue_capacity" 15
            }
          }
        }
        span(2) {
          operationName "service-call"
          childOf(span(1))
          errored false
        }
      }
    }

    where:
    measuredEnabled | tagMetricsEnabled
    false           | false
    false           | true
    true            | false
    true            | true
  }

  def "decorate supplier with thread pool bulkhead"() {
    setup:
    def bulkhead = Mock(ThreadPoolBulkhead)
    bulkhead.getName() >> "thread-pool-bulkhead-2"
    bulkhead.getMetrics() >> Mock(ThreadPoolBulkhead.Metrics)

    when:
    Supplier<CompletableFuture<String>> supplier = ThreadPoolBulkhead.decorateSupplier(bulkhead) {
      CompletableFuture.completedFuture(serviceCall("supplier-result"))
    }

    then:
    def result
    runUnderTrace("parent") {
      result = supplier.get().get()
    }
    result == "supplier-result"

    then:
    assertTraces(1) {
      trace(3) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
        }
        span(1) {
          operationName "resilience4j"
          childOf(span(0))
          tags {
            "resilience4j.bulkhead.name" "thread-pool-bulkhead-2"
            "resilience4j.bulkhead.type" "threadpool"
          }
        }
        span(2) {
          operationName "service-call"
          childOf(span(1))
        }
      }
    }
  }

  String serviceCall(String value) {
    AgentTracer.get().startSpan("service-call").finish()
    return value
  }
}
