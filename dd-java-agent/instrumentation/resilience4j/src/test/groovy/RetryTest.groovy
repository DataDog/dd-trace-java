import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentScope
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
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

  // TODO use Retry mocks to test more scenarios
  // TODO test all io.github.resilience4j.decorators.Decorators.of* decorators
  // TODO test throwing serviceCall
  // TODO test stacked decorators

  def "decorateCheckedSupplier"() {
    when:
    CheckedSupplier<String> supplier = Decorators
      .ofCheckedSupplier{serviceCall("foobar")}
      .withRetry(Retry.ofDefaults("id"))
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
      .withRetry(Retry.ofDefaults("id"))
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
      .withRetry(Retry.ofDefaults("id"), Executors.newSingleThreadScheduledExecutor())
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
      .withRetry(Retry.of("id", RetryConfig.custom().maxAttempts(2).build()))
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
          operationName "resilience4j.retry"
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
      .ofCompletionStage {
        CompletableFuture.supplyAsync({
          serviceCallErr(new IllegalStateException("error"))
        }, executor)
      }
      .withRetry(Retry.of("id", RetryConfig.custom().maxAttempts(2).build()), Executors.newSingleThreadScheduledExecutor())
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
          operationName "resilience4j.retry"
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
          operationName "resilience4j.retry"
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
    try (def ignored = AgentTracer.activateSpan(span)) {
      throw e
    } finally {
      span.finish()
    }
  }
}
