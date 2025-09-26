import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.github.resilience4j.reactor.retry.RetryOperator
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import reactor.core.publisher.ConnectableFlux
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers
import static datadog.trace.agent.test.utils.TraceUtils.runnableUnderTrace

class RetryTest extends InstrumentationSpecification {

  def "decorate span with retry"() {
    setup:
    injectSysConfig(TraceInstrumentationConfig.RESILIENCE4J_MEASURED_ENABLED, measuredEnabled.toString())
    injectSysConfig(TraceInstrumentationConfig.RESILIENCE4J_TAG_METRICS_ENABLED, tagMetricsEnabled.toString())

    def ms = Mock(Retry.Metrics)
    def rc = Mock(RetryConfig)
    def rt = Mock(Retry)
    def cx = Mock(Retry.Context)
    rt.getName() >> "rt1"
    rt.getRetryConfig() >> rc
    rt.getMetrics() >> ms
    rt.context() >> cx
    rc.getMaxAttempts() >> 23
    rc.isFailAfterMaxAttempts() >> true
    ms.getNumberOfFailedCallsWithoutRetryAttempt() >> 1
    ms.getNumberOfFailedCallsWithRetryAttempt() >> 2
    ms.getNumberOfSuccessfulCallsWithoutRetryAttempt() >> 3
    ms.getNumberOfSuccessfulCallsWithRetryAttempt() >> 4

    when:
    Flux<String> connection = Flux.just("abc")
      .map({ serviceCall(it)})
      .transformDeferred(RetryOperator.of(rt))
      .publish()

    runnableUnderTrace("parent") {
      connection.connect()
    }

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
            "resilience4j.retry.name" "rt1"
            "resilience4j.retry.max_attempts" 23
            "resilience4j.retry.fail_after_max_attempts" true
            if (tagMetricsEnabled) {
              "resilience4j.retry.metrics.failed_without_retry" 1
              "resilience4j.retry.metrics.failed_with_retry" 2
              "resilience4j.retry.metrics.success_without_retry" 3
              "resilience4j.retry.metrics.success_with_retry" 4
            }
            defaultTags()
          }
        }
        span(2) {
          operationName "serviceCall/abc"
          childOf span(1)
          errored false
        }
      }
    }

    where:
    measuredEnabled | tagMetricsEnabled
    true            | true
    false           | false
    true            | false
    false           | true
  }

  def "decorateCompletionStage retry Flux on error"() {
    setup:
    ConnectableFlux<String> connection = Flux.just("abc")
      .map({ serviceCallErr(it, new IllegalStateException("error"))})
      .transformDeferred(RetryOperator.of(Retry.of("R0", RetryConfig.custom().maxAttempts(2).build())))
      .publishOn(Schedulers.boundedElastic())
      .publish()

    when:
    connection.subscribe {
      // won't show up because of errors upstream
      runnableUnderTrace("child-" + it) {}
    }

    runnableUnderTrace("parent") {
      connection.connect()
    }

    then:
    assertTraces(1) {
      trace(4) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
          parent()
          errored false
        }
        span(1) {
          operationName "resilience4j"
          childOf span(0)
          errored false
        }
        span(2) {
          operationName "serviceCallErr/abc"
          childOf span(1)
          errored false
        }
        // second attempt span under the retry span
        span(3) {
          operationName "serviceCallErr/abc"
          childOf span(1)
          errored false
        }
      }
    }
  }

  def "decorateCompletionStage retry Mono on error"() {
    setup:
    Mono<String> mono = Mono.just("abc")
      .map({ serviceCallErr(it, new IllegalStateException("error"))})
      .transformDeferred(RetryOperator.of(Retry.of("R0", RetryConfig.custom().maxAttempts(2).build())))

    when:
    runnableUnderTrace("parent") {
      mono.subscribe {
        // won't show up because of errors upstream
        runnableUnderTrace("child-" + it) {}
      }
    }

    then:
    assertTraces(1) {
      trace(4) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
          parent()
          errored false
        }
        span(1) {
          operationName "resilience4j"
          childOf span(0)
          errored false
        }
        span(2) {
          operationName "serviceCallErr/abc"
          childOf span(1)
          errored false
        }
        // second attempt span under the retry span
        span(3) {
          operationName "serviceCallErr/abc"
          childOf span(1)
          errored false
        }
      }
    }
  }

  def <T> T serviceCall(T value) {
    AgentTracer.startSpan("test", "serviceCall/$value").finish()
    value
  }

  void serviceCallErr(String value, IllegalStateException e) {
    def span = AgentTracer.startSpan("test", "serviceCallErr/$value")
    def scope = AgentTracer.activateSpan(span)
    try {
      throw e
    } finally {
      scope.close()
      span.finish()
    }
  }
}
