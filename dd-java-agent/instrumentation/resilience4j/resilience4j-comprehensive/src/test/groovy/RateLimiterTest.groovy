import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.github.resilience4j.ratelimiter.RateLimiter

import java.util.concurrent.Callable
import java.util.function.Supplier

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class RateLimiterTest extends InstrumentationSpecification {

  def "decorate span with rate-limiter"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.RESILIENCE4J_MEASURED_ENABLED, measuredEnabled.toString())
    injectSysConfig(TraceInstrumentationConfig.RESILIENCE4J_TAG_METRICS_ENABLED, tagMetricsEnabled.toString())

    def metrics = Mock(RateLimiter.Metrics)
    def rateLimiter = Mock(RateLimiter)
    rateLimiter.getName() >> "rate-limiter-1"
    rateLimiter.acquirePermission() >> true
    rateLimiter.getMetrics() >> metrics
    metrics.getAvailablePermissions() >> 45
    metrics.getNumberOfWaitingThreads() >> 2

    when:
    Supplier<String> supplier = RateLimiter.decorateSupplier(rateLimiter) { serviceCall("result") }

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
            "resilience4j.rate_limiter.name" "rate-limiter-1"
            if (tagMetricsEnabled) {
              "resilience4j.rate_limiter.metrics.available_permissions" 45
              "resilience4j.rate_limiter.metrics.number_of_waiting_threads" 2
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

  def "decorate callable with rate-limiter"() {
    setup:
    def rateLimiter = Mock(RateLimiter)
    rateLimiter.getName() >> "rate-limiter-2"
    rateLimiter.acquirePermission() >> true
    rateLimiter.getMetrics() >> Mock(RateLimiter.Metrics)

    when:
    Callable<String> callable = RateLimiter.decorateCallable(rateLimiter) { serviceCall("callable-result") }

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
            "resilience4j.rate_limiter.name" "rate-limiter-2"
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
