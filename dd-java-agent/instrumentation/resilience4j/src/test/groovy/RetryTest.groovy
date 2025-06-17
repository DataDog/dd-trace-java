import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import io.github.resilience4j.core.functions.CheckedSupplier
import io.github.resilience4j.decorators.Decorators
import io.github.resilience4j.retry.Retry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
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

  //  @Ignore("first need to implement async decorator and then see how to implement stacking properly")
  //  def "decorateSupplier stacked cbs"() {
  //    when:
  //    Supplier<String> supplier = Decorators
  //      .ofSupplier{serviceCall("foobar")}
  //      .withCircuitBreaker(CircuitBreaker.ofDefaults("a"))
  //      .withCircuitBreaker(CircuitBreaker.ofDefaults("b"))
  //      .decorate()
  //
  //    then:
  //    runUnderTrace("parent") { supplier.get() } == "foobar"
  //    and:
  //    assertExpectedTrace()
  //  }

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
}
