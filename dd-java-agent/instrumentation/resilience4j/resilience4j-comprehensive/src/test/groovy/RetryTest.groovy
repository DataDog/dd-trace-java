import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig

import java.time.Duration
import java.util.concurrent.Callable
import java.util.function.Supplier

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class RetryTest extends InstrumentationSpecification {

  def "decorate supplier with retry"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.RESILIENCE4J_MEASURED_ENABLED, measuredEnabled.toString())

    def config = RetryConfig.custom()
      .maxAttempts(3)
      .waitDuration(Duration.ofMillis(100))
      .build()
    def retry = Mock(Retry)
    retry.getName() >> "retry-1"
    retry.getRetryConfig() >> config

    when:
    Supplier<String> supplier = Retry.decorateSupplier(retry) { serviceCall("result") }

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
            "resilience4j.retry.name" "retry-1"
            "resilience4j.retry.max_attempts" 3
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
    measuredEnabled << [false, true]
  }

  def "decorate callable with retry"() {
    setup:
    def config = RetryConfig.custom()
      .maxAttempts(5)
      .waitDuration(Duration.ofMillis(50))
      .build()
    def retry = Mock(Retry)
    retry.getName() >> "retry-2"
    retry.getRetryConfig() >> config

    when:
    Callable<String> callable = Retry.decorateCallable(retry) { serviceCall("callable-result") }

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
            "resilience4j.retry.name" "retry-2"
            "resilience4j.retry.max_attempts" 5
          }
        }
        span(2) {
          operationName "service-call"
          childOf(span(1))
        }
      }
    }
  }

  def "retry with exponential backoff"() {
    setup:
    def config = RetryConfig.custom()
      .maxAttempts(4)
      .waitDuration(Duration.ofMillis(100))
      .intervalFunction({ attempt -> Duration.ofMillis(100L * (1L << attempt)) })
      .build()
    def retry = Mock(Retry)
    retry.getName() >> "retry-exponential"
    retry.getRetryConfig() >> config

    when:
    Supplier<String> supplier = Retry.decorateSupplier(retry) { serviceCall("exponential-result") }

    then:
    runUnderTrace("parent") { supplier.get() } == "exponential-result"

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
            "resilience4j.retry.name" "retry-exponential"
            "resilience4j.retry.max_attempts" 4
          }
        }
        span(2) {
          operationName "service-call"
          childOf(span(1))
        }
      }
    }
  }

  def "decorate runnable with retry"() {
    setup:
    def config = RetryConfig.custom()
      .maxAttempts(2)
      .build()
    def retry = Mock(Retry)
    retry.getName() >> "retry-runnable"
    retry.getRetryConfig() >> config

    when:
    Runnable runnable = Retry.decorateRunnable(retry) {
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
            "resilience4j.retry.name" "retry-runnable"
            "resilience4j.retry.max_attempts" 2
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
