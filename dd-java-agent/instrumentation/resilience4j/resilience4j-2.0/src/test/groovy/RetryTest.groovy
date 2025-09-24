import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.api.config.TraceInstrumentationConfig
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.github.resilience4j.core.functions.CheckedFunction
import io.github.resilience4j.core.functions.CheckedRunnable
import io.github.resilience4j.core.functions.CheckedSupplier
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.function.Function
import java.util.function.Supplier

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class RetryTest extends InstrumentationSpecification {
  static singleThreadExecutor = Executors.newSingleThreadExecutor()

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
    Supplier<String> supplier = Retry.decorateSupplier(rt) { serviceCall("foobar") }

    then:
    runUnderTrace("parent"){supplier.get()} == "foobar"

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
          operationName "serviceCall"
          childOf(span(1))
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

  def "decorateCompletionStage"() {
    setup:
    when:
    def scheduler = Executors.newSingleThreadScheduledExecutor()
    Supplier<CompletionStage<String>> supplier = Retry.decorateCompletionStage(
      Retry.ofDefaults("rt"), scheduler, {
        CompletableFuture.supplyAsync({
          serviceCall("foobar")
        }, singleThreadExecutor)
      }
      )

    then:
    runUnderTrace("parent"){supplier.get().toCompletableFuture()}.get() == "foobar"
    and:
    assertExpectedTrace()
  }

  def "decorateCheckedSupplier"() {
    when:
    CheckedSupplier<String> supplier = Retry.decorateCheckedSupplier(Retry.ofDefaults("rt")) { serviceCall("foobar") }

    then:
    runUnderTrace("parent"){supplier.get()} == "foobar"
    and:
    assertExpectedTrace()
  }

  def "decorateCheckedRunnable"() {
    when:
    CheckedRunnable runnable = Retry.decorateCheckedRunnable(Retry.ofDefaults("rt")) { serviceCall("foobar") }

    then:
    runUnderTrace("parent") {
      runnable.run()
      "a"
    }
    and:
    assertExpectedTrace()
  }

  def "decorateCheckedFunction"() {
    when:
    CheckedFunction<String, String> function = Retry.decorateCheckedFunction(Retry.ofDefaults("rt")) { v -> serviceCall("foobar-$v") }

    then:
    runUnderTrace("parent") { function.apply("test") } == "foobar-test"
    and:
    assertExpectedTrace()
  }

  def "decorateSupplier"() {
    when:
    Supplier<String> supplier = Retry.decorateSupplier(Retry.ofDefaults("rt")) { serviceCall("foobar") }

    then:
    runUnderTrace("parent"){supplier.get()} == "foobar"
    and:
    assertExpectedTrace()
  }

  def "decorateCallable"() {
    when:
    Callable<String> callable = Retry.decorateCallable(Retry.ofDefaults("rt")) { serviceCall("foobar") }

    then:
    runUnderTrace("parent"){callable.call()} == "foobar"
    and:
    assertExpectedTrace()
  }

  def "decorateRunnable"() {
    when:
    Runnable runnable = Retry.decorateRunnable(Retry.ofDefaults("rt")) { serviceCall("foobar") }

    then:
    runUnderTrace("parent") {
      runnable.run()
      "a"
    }
    and:
    assertExpectedTrace()
  }

  def "decorateFunction"() {
    when:
    Function<String, String> function = Retry.decorateFunction(Retry.ofDefaults("rt")) { v -> serviceCall("foobar-$v") }

    then:
    runUnderTrace("parent"){function.apply("test")} == "foobar-test"
    and:
    assertExpectedTrace()
  }

  def "decorateSupplier retry twice on error -- second call scoped by the r4j span"() {
    when:
    Supplier<String> supplier = Retry.decorateSupplier(
      Retry.of("rt", RetryConfig.custom().maxAttempts(2).build())
      ) { serviceCallErr(new IllegalStateException("error")) }
    runUnderTrace("parent") { supplier.get() }
    then:
    thrown(IllegalStateException)
    and:
    assertTraces(1) {
      trace(4) {
        sortSpansByStart()
        span(0) {
          operationName "parent"
          parent()
          errored true // b/o unhandled exception
        }
        span(1) {
          operationName "resilience4j"
          childOf span(0)
          errored false
        }
        span(2) {
          operationName "serviceCall"
          childOf span(1)
          errored false
        }
        // second attempt span under the retry span
        span(3) {
          operationName "serviceCall"
          childOf span(1)
          errored false
        }
      }
    }
  }

  def "decorateCompletionStage retry twice on error -- second call scoped by the r4j span"() {
    setup:
    def scheduler = Executors.newSingleThreadScheduledExecutor()
    Supplier<CompletionStage<String>> supplier = Retry.decorateCompletionStage(
      Retry.of("rt", RetryConfig.custom().maxAttempts(2).build()), scheduler, {
        CompletableFuture.supplyAsync({
          serviceCallErr(new IllegalStateException("error"))
        }, singleThreadExecutor)
      }
      )

    when:
    def future = runUnderTrace("parent") { supplier.get().toCompletableFuture() }
    future.get()

    then:
    def ee = thrown(ExecutionException)
    ee.cause instanceof IllegalStateException
    and:
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
          operationName "serviceCall"
          childOf span(1)
          errored false
        }
        // second attempt span under the retry span
        span(3) {
          operationName "serviceCall"
          childOf span(1)
          errored false
        }
      }
    }
  }

  private void assertExpectedTrace() {
    assertTraces(1) {
      trace(3) {
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
          operationName "serviceCall"
          childOf span(1)
          errored false
        }
      }
    }
  }

  def <T> T serviceCall(T value) {
    AgentTracer.startSpan("test", "serviceCall").finish()
    value
  }

  void serviceCallErr(IllegalStateException e) {
    def span = AgentTracer.startSpan("test", "serviceCall")
    def scope = AgentTracer.activateSpan(span)
    try {
      throw e
    } finally {
      scope.close()
      span.finish()
    }
  }
}
