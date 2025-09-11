package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.core.functions.CheckedSupplier;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class CircuitBreakerInstrumentation extends Resilience4jInstrumentation {

  private static final String CIRCUIT_BREAKER_FQCN =
      "io.github.resilience4j.circuitbreaker.CircuitBreaker";

  public CircuitBreakerInstrumentation() {
    super("resilience4j-circuitbreaker", "resilience4j-core");
  }

  @Override
  public String instrumentedType() {
    return CIRCUIT_BREAKER_FQCN;
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCheckedSupplier"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(returns(named(CHECKED_SUPPLIER_FQCN))),
        CircuitBreakerInstrumentation.class.getName() + "$CheckedSupplierAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateSupplier"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(returns(named(SUPPLIER_FQCN))),
        CircuitBreakerInstrumentation.class.getName() + "$SupplierAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCompletionStage"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(returns(named(SUPPLIER_FQCN))),
        CircuitBreakerInstrumentation.class.getName() + "$CompletionStageAdvice");
  }

  public static class CheckedSupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) CircuitBreaker circuitBreaker,
        @Advice.Return(readOnly = false) CheckedSupplier<?> outbound) {
      outbound =
          new ContextHolder.CheckedSupplierWithContext<>(
              outbound, CircuitBreakerDecorator.DECORATE, circuitBreaker);
    }
  }

  public static class SupplierAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) CircuitBreaker circuitBreaker,
        @Advice.Return(readOnly = false) Supplier<?> outbound) {
      outbound =
          new ContextHolder.SupplierWithContext<>(
              outbound, CircuitBreakerDecorator.DECORATE, circuitBreaker);
    }
  }

  public static class CompletionStageAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class)
    public static void afterExecute(
        @Advice.Argument(value = 0) CircuitBreaker circuitBreaker,
        @Advice.Return(readOnly = false) Supplier<CompletionStage<?>> outbound) {
      outbound =
          new ContextHolder.SupplierCompletionStageWithContext<>(
              outbound, CircuitBreakerDecorator.DECORATE, circuitBreaker);
    }
  }
}
