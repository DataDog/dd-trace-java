

import datadog.trace.agent.test.AgentTestRunner
import datadog.trace.agent.test.utils.TraceUtils
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.core.functions.CheckedSupplier
import io.github.resilience4j.decorators.Decorators
import spock.lang.Ignore

import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.function.Supplier

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class CircuitBreakerTest extends AgentTestRunner {

  // TODO use CircuitBreaker mocks to test more scenarios
  // TODO test all io.github.resilience4j.decorators.Decorators.of* decorators
  // TODO test throwing serviceCall
  // TODO test stacked decorators

  def "decorateCheckedSupplier"() {
    when:
    CheckedSupplier<String> supplier = Decorators
      .ofCheckedSupplier { serviceCall("foobar") }
      .withCircuitBreaker(CircuitBreaker.ofDefaults("id"))
      .decorate()

    then:
    runUnderTrace("parent") { supplier.get() } == "foobar"
    and:
    assertExpectedTrace()
  }

  def "decorateSupplier"() {
    when:
    Supplier<String> supplier = Decorators
      .ofSupplier { serviceCall("foobar") }
      .withCircuitBreaker(CircuitBreaker.ofDefaults("id"))
      .decorate()

    then:
    runUnderTrace("parent") { supplier.get() } == "foobar"
    and:
    assertExpectedTrace()
  }

  ExecutorService executor = Executors.newFixedThreadPool(1)
  ExecutorService executor2 = Executors.newFixedThreadPool(1)

  def "decorateCompletionStage"() {
    when:
    Supplier<CompletionStage<String>> supplier = Decorators
    .ofCompletionStage { CompletableFuture.supplyAsync({ serviceCall("foobar") }, executor).thenApplyAsync(v -> v, executor2) }
    .withCircuitBreaker(CircuitBreaker.ofDefaults("id"))
    .decorate()

    then:
    //TODO force the future run in a separate thread, now serviceCall calls Thread.sleep to ensure that
    def future = runUnderTrace("parent") { supplier.get().toCompletableFuture() }
    future.get() == "foobar"
    and:
    assertExpectedTrace()
  }

  @Ignore("first need to implement async decorator and then see how to implement stacking properly")
  def "decorateSupplier stacked cbs"() {
    when:
    Supplier<String> supplier = Decorators
      .ofSupplier { serviceCall("foobar") }
      .withCircuitBreaker(CircuitBreaker.ofDefaults("a"))
      .withCircuitBreaker(CircuitBreaker.ofDefaults("b"))
      .decorate()

    then:
    runUnderTrace("parent") { supplier.get() } == "foobar"
    and:
    assertExpectedTrace()
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

  def <T> T serviceCall(T t) {
    AgentTracer.startSpan("test", "serviceCall").finish()
    Thread.sleep(100) // TODO wait here to make whenComplete run in a separate thread; How to guarantee that? use semaphore?
    return t
  }
}
