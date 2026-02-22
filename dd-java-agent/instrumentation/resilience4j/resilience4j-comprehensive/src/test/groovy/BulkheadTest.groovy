import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.github.resilience4j.bulkhead.Bulkhead

import java.util.concurrent.Callable
import java.util.function.Supplier

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class BulkheadTest extends InstrumentationSpecification {

  def "decorate supplier with bulkhead"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.RESILIENCE4J_MEASURED_ENABLED, measuredEnabled.toString())
    injectSysConfig(TraceInstrumentationConfig.RESILIENCE4J_TAG_METRICS_ENABLED, tagMetricsEnabled.toString())

    def metrics = Mock(Bulkhead.Metrics)
    def bulkhead = Mock(Bulkhead)
    bulkhead.getName() >> "bulkhead-1"
    bulkhead.tryAcquirePermission() >> true
    bulkhead.getMetrics() >> metrics
    metrics.getAvailableConcurrentCalls() >> 8
    metrics.getMaxAllowedConcurrentCalls() >> 10

    when:
    Supplier<String> supplier = Bulkhead.decorateSupplier(bulkhead) { serviceCall("result") }

    then:
    runUnderTrace("parent") { supplier.get() } == "result"

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
            "resilience4j.bulkhead.name" "bulkhead-1"
            "resilience4j.bulkhead.type" "semaphore"
            if (tagMetricsEnabled) {
              "resilience4j.bulkhead.metrics.available_concurrent_calls" 8
              "resilience4j.bulkhead.metrics.max_allowed_concurrent_calls" 10
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

  def "decorate callable with bulkhead"() {
    setup:
    def bulkhead = Mock(Bulkhead)
    bulkhead.getName() >> "bulkhead-2"
    bulkhead.tryAcquirePermission() >> true
    bulkhead.getMetrics() >> Mock(Bulkhead.Metrics)

    when:
    Callable<String> callable = Bulkhead.decorateCallable(bulkhead) { serviceCall("callable-result") }

    then:
    runUnderTrace("parent") { callable.call() } == "callable-result"

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
            "resilience4j.bulkhead.name" "bulkhead-2"
            "resilience4j.bulkhead.type" "semaphore"
          }
        }
        span(2) {
          operationName "service-call"
          childOf(span(1))
        }
      }
    }
  }

  def "decorate runnable with bulkhead"() {
    setup:
    def bulkhead = Mock(Bulkhead)
    bulkhead.getName() >> "bulkhead-3"
    bulkhead.tryAcquirePermission() >> true
    bulkhead.getMetrics() >> Mock(Bulkhead.Metrics)

    when:
    Runnable runnable = Bulkhead.decorateRunnable(bulkhead) {
      serviceCall("runnable-executed")
    }

    then:
    runUnderTrace("parent") { runnable.run() }

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
            "resilience4j.bulkhead.name" "bulkhead-3"
            "resilience4j.bulkhead.type" "semaphore"
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
