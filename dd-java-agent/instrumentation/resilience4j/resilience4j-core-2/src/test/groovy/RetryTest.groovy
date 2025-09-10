import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import datadog.trace.bootstrap.instrumentation.api.Tags
import io.github.resilience4j.core.functions.CheckedSupplier
import io.github.resilience4j.decorators.Decorators
import io.github.resilience4j.retry.Retry
import io.github.resilience4j.retry.RetryConfig
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.function.Supplier

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class RetryTest extends AgentTestRunner {
  // TODO test all io.github.resilience4j.decorators.Decorators.of* decorators
  // TODO test throwing serviceCall

  def "decorate span with retry"() {
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
    Supplier<String> supplier = Decorators
      .ofSupplier{serviceCall("foobar")}
      .withRetry(rt)
      .decorate()

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
          tags {
            "$Tags.COMPONENT" "resilience4j"
            "$Tags.SPAN_KIND" Tags.SPAN_KIND_INTERNAL
            "resilience4j.retry.name" "rt1"
            "resilience4j.retry.max_attempts" 23
            "resilience4j.retry.fail_after_max_attempts" true
            "resilience4j.retry.metrics.failed_without_retry" 1
            "resilience4j.retry.metrics.failed_with_retry" 2
            "resilience4j.retry.metrics.success_without_retry" 3
            "resilience4j.retry.metrics.success_with_retry" 4
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
  }


  def "decorateCheckedSupplier"() {
    when:
    CheckedSupplier<String> supplier = Decorators
      .ofCheckedSupplier{serviceCall("foobar")}
      .withRetry(Retry.ofDefaults("rt"))
      .decorate()

    then:
    runUnderTrace("parent"){supplier.get()} == "foobar"
    and:
    assertExpectedTrace()
  }

  def "decorateSupplier"() {
    when:
    Supplier<String> supplier = Decorators
      .ofSupplier{serviceCall("foobar")}
      .withRetry(Retry.ofDefaults("rt"))
      .decorate()

    then:
    runUnderTrace("parent"){supplier.get()} == "foobar"
    and:
    assertExpectedTrace()
  }

  def "decorateCompletionStage"() {
    setup:
    def executor = Executors.newSingleThreadExecutor()
    Thread testThread = Thread.currentThread()
    when:
    Supplier<CompletionStage<String>> supplier = Decorators
      .ofCompletionStage{
        CompletableFuture.supplyAsync({
          // prevent completion on the same thread
          Thread.sleep(100)
          serviceCall("foobar")
        }, executor).whenComplete { r, e ->
          assert Thread.currentThread() != testThread,
          "Make sure that the thread running whenComplete is different from the one running the test. " +
          "This verifies that the scope we create does not cross the thread boundaries. " +
          "If it fails, ensure that the provided future isn't completed immediately. Otherwise, the callback will be called on the caller thread."
        }
      }
      .withRetry(Retry.ofDefaults("rt"), Executors.newSingleThreadScheduledExecutor())
      .decorate()

    then:
    def future = runUnderTrace("parent"){supplier.get().toCompletableFuture()}
    future.get() == "foobar"
    and:
    assertExpectedTrace()
  }

  def "decorateSupplier retry twice on error"() {
    when:
    Supplier<String> supplier = Decorators
      .ofSupplier{serviceCallErr(new IllegalStateException("error"))}
      .withRetry(Retry.of("rt", RetryConfig.custom().maxAttempts(2).build()))
      .decorate()
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

  def "decorateCompletionStage retry twice on error"() {
    setup:
    def executor = Executors.newSingleThreadExecutor()
    when:
    Supplier<CompletionStage<String>> supplier = Decorators
      .<String>ofCompletionStage {
        CompletableFuture.supplyAsync({
          serviceCallErr(new IllegalStateException("error"))
        }, executor)
      }
      .withRetry(Retry.of("rt", RetryConfig.custom().maxAttempts(2).build()), Executors.newSingleThreadScheduledExecutor())
      .decorate()
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
