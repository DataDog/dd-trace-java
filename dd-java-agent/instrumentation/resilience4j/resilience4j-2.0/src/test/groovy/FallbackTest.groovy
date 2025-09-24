import datadog.trace.agent.test.InstrumentationSpecification
import datadog.trace.bootstrap.instrumentation.api.AgentTracer
import io.github.resilience4j.core.functions.CheckedBiFunction
import io.github.resilience4j.core.functions.CheckedFunction
import io.github.resilience4j.decorators.Decorators
import io.github.resilience4j.decorators.Decorators.DecorateSupplier
import io.github.resilience4j.decorators.Decorators.DecorateCallable
import io.github.resilience4j.decorators.Decorators.DecorateCheckedSupplier
import io.github.resilience4j.core.functions.CheckedSupplier
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.function.BiFunction
import java.util.function.Function
import java.util.function.Predicate
import java.util.function.Supplier
import java.util.function.UnaryOperator

import static datadog.trace.agent.test.utils.TraceUtils.runUnderTrace

class FallbackTest extends InstrumentationSpecification {
  static singleThreadExecutor = Executors.newSingleThreadExecutor()

  def "ofSupplier"(DecorateSupplier<String> decorateSupplier) {
    setup:
    def supplier = decorateSupplier.decorate()

    when:
    def result = runUnderTrace("parent") { supplier.get() }

    then:
    result == "fallbackResult"
    and:
    assertExpectedTrace()

    where:
    decorateSupplier << [
      Decorators.ofSupplier{
        serviceCallErr(new IllegalStateException("test"))
      }.withFallback({ t -> serviceCall("fallbackResult", "fallbackCall") } as Function<Throwable, String>)
      ,
      Decorators.ofSupplier{
        serviceCall("badResult", "serviceCall")
      }.withFallback({ it == "badResult" } as Predicate<String>, { serviceCall("fallbackResult", "fallbackCall") } as UnaryOperator<String>)
      ,
      Decorators.ofSupplier{
        serviceCallErr(new IllegalStateException("test"))
      }
      .withFallback({ v, t -> serviceCall("fallbackResult", "fallbackCall") } as BiFunction<String, Throwable, String>)
      ,
      Decorators.ofSupplier{
        serviceCallErr(new IllegalStateException("test"))
      }
      .withFallback(List.of(IllegalStateException), {t -> serviceCall("fallbackResult", "fallbackCall") } as Function<Throwable, String>),
    ]
  }

  def "ofCheckedSupplier"(DecorateCheckedSupplier<String> decorateCheckedSupplier) {
    setup:
    CheckedSupplier<String> supplier = decorateCheckedSupplier.decorate()

    when:
    def result = runUnderTrace("parent") { supplier.get() }

    then:
    result == "fallbackResult"
    and:
    assertExpectedTrace()

    where:
    decorateCheckedSupplier << [
      Decorators.ofCheckedSupplier{
        serviceCallErr(new IllegalStateException("test"))
      }.withFallback({ t -> serviceCall("fallbackResult", "fallbackCall") } as CheckedFunction<Throwable, String>)
      ,
      Decorators.ofCheckedSupplier{
        serviceCall("badResult", "serviceCall")
      }.withFallback({ it == "badResult" } as Predicate<String>, { serviceCall("fallbackResult", "fallbackCall") } as CheckedFunction<String, String>)
      ,
      Decorators.ofCheckedSupplier{
        serviceCallErr(new IllegalStateException("test"))
      }
      .withFallback({ v, t -> serviceCall("fallbackResult", "fallbackCall") } as CheckedBiFunction<String, Throwable, String>)
      ,
      Decorators.ofCheckedSupplier{
        serviceCallErr(new IllegalStateException("test"))
      }
      .withFallback(List.of(IllegalStateException), { t -> serviceCall("fallbackResult", "fallbackCall") } as CheckedFunction<Throwable, String>),
    ]
  }

  def "ofCallable"(DecorateCallable<String> decorateCallable) {
    setup:
    def callable = decorateCallable.decorate()

    when:
    def result = runUnderTrace("parent") { callable.call() }

    then:
    result == "fallbackResult"
    and:
    assertExpectedTrace()

    where:
    decorateCallable << [
      Decorators.ofCallable{ v ->
        serviceCallErr(new IllegalStateException("test"))
      }.withFallback({ t -> serviceCall("fallbackResult", "fallbackCall") } as Function<Throwable, String>)
      ,
      Decorators.ofCallable{ v ->
        serviceCall("badResult", "serviceCall")
      }.withFallback({ it == "badResult" } as Predicate<String>, { serviceCall("fallbackResult", "fallbackCall") } as UnaryOperator<String>)
      ,
      Decorators.ofCallable{ v ->
        serviceCallErr(new IllegalStateException("test"))
      }
      .withFallback({ v, t -> serviceCall("fallbackResult", "fallbackCall") } as BiFunction<String, Throwable, String>)
      ,
      Decorators.ofCallable{ v ->
        serviceCallErr(new IllegalStateException("test"))
      }
      .withFallback(List.of(IllegalStateException), { t -> serviceCall("fallbackResult", "fallbackCall") } as Function<Throwable, String>),
    ]
  }

  def "ofCompletionStage"(Supplier<CompletionStage<String>> supplier) {
    when:
    def future = runUnderTrace("parent") { supplier.get().toCompletableFuture() }

    then:
    future.get() == "fallbackResult"

    then:
    assertExpectedTrace()

    where:
    supplier << [
      Decorators
      .ofCompletionStage {
        CompletableFuture.supplyAsync({
          serviceCallErr(new IllegalStateException("test"))
        }, singleThreadExecutor)
      }
      .withFallback({ Throwable t ->
        serviceCall("fallbackResult", "fallbackCall")
      } as Function<Throwable, String>)
      .decorate(),
      Decorators
      .ofCompletionStage {
        CompletableFuture.supplyAsync({
          serviceCall("badResult", "serviceCall")
        }, singleThreadExecutor)
      }
      .withFallback({ it == "badResult" } as Predicate<String>, { serviceCall("fallbackResult", "fallbackCall") } as UnaryOperator<String>)
      .decorate(),
      Decorators
      .ofCompletionStage {
        CompletableFuture.supplyAsync({
          serviceCallErr(new IllegalStateException("test"))
        }, singleThreadExecutor)
      }
      .withFallback({ v, t -> serviceCall("fallbackResult", "fallbackCall") } as BiFunction<String, Throwable, String>)
      .decorate(),
      Decorators
      .ofCompletionStage {
        CompletableFuture.supplyAsync({
          serviceCallErr(new IllegalStateException("test"))
        }, singleThreadExecutor)
      }
      .withFallback(List.of(IllegalStateException), { t -> serviceCall("fallbackResult", "fallbackCall") } as Function<Throwable, String>)
      .decorate(),
    ]
  }

  private void assertExpectedTrace() {
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
        span(3) {
          operationName "fallbackCall"
          childOf span(1)
          errored false
        }
      }
    }
  }

  def <T> T serviceCall(T value, String name) {
    AgentTracer.startSpan("test", name).finish()
    value
  }

  def serviceCallErr(IllegalStateException e) {
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
