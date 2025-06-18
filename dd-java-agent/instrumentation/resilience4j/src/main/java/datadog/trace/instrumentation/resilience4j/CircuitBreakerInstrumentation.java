package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.ArrayList;
import java.util.Arrays;
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
            .and(
                namedOneOf(
                    "decorateCheckedSupplier",
                    // TODO add all the other decorator methods here
                    "decorateSupplier"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN))),
        CircuitBreakerInstrumentation.class.getName() + "$SyncDecoratorsAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCompletionStage"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(takesArgument(1, named("java.util.function.Supplier"))),
        CircuitBreakerInstrumentation.class.getName() + "$CompletionStageAdvice");
  }

  @Override
  public String[] muzzleIgnoredClassNames() {
    ArrayList<String> ignored = new ArrayList<>(Arrays.asList(helperClassNames()));
    // Prevent a LinkageError caused by a reference to the instrumented interface by excluding these
    // from being loaded by the muzzle check.
    ignored.add(packageName + ".CircuitBreakerWrapper");
    return ignored.toArray(new String[0]);
  }

  public static class SyncDecoratorsAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beforeExecute(
        @Advice.Argument(value = 0, readOnly = false) CircuitBreaker circuitBreaker) {
      circuitBreaker = new CircuitBreakerWrapper(circuitBreaker, DDContext.circuitBreaker());
    }
  }

  public static class CompletionStageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beforeExecute(
        @Advice.Argument(value = 0, readOnly = false) CircuitBreaker circuitBreaker,
        @Advice.Argument(value = 1, readOnly = false) Supplier<CompletionStage<?>> supplier) {
      DDContext ddContext = DDContext.circuitBreaker();
      circuitBreaker = new CircuitBreakerWrapper(circuitBreaker, ddContext);
      supplier = ddContext.wrap(supplier);
    }
  }
}
