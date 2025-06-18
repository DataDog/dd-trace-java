package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
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
    super("resilience4j-circuitbreaker");
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
            .and(takesArgument(1, named("io.github.resilience4j.core.functions.CheckedSupplier"))),
        CircuitBreakerInstrumentation.class.getName() + "$CheckedSupplierAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateSupplier"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(takesArgument(1, named("java.util.function.Supplier"))),
        CircuitBreakerInstrumentation.class.getName() + "$SupplierAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCompletionStage"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(takesArgument(1, named("java.util.function.Supplier"))),
        CircuitBreakerInstrumentation.class.getName() + "$CompletionStageAdvice");
  }

  public static class CheckedSupplierAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beforeExecute(
        @Advice.Argument(value = 0) CircuitBreaker circuitBreaker,
        @Advice.Argument(value = 1, readOnly = false) CheckedSupplier<?> supplier) {
      DDContext ddContext = DDContext.of(circuitBreaker);
      supplier = ddContext.tracedCheckedSupplier(supplier);
    }
  }

  public static class SupplierAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beforeExecute(
        @Advice.Argument(value = 0) CircuitBreaker circuitBreaker,
        @Advice.Argument(value = 1, readOnly = false) Supplier<?> supplier) {
      DDContext ddContext = DDContext.of(circuitBreaker);
      supplier = ddContext.tracedSupplier(supplier);
    }
  }

  public static class CompletionStageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beforeExecute(
        @Advice.Argument(value = 0, readOnly = false) CircuitBreaker circuitBreaker,
        @Advice.Argument(value = 1, readOnly = false) Supplier<CompletionStage<?>> supplier) {
      DDContext ddContext = DDContext.of(circuitBreaker);
      supplier = ddContext.tracedCompletionStage(supplier);
    }
  }
}
