package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.namedOneOf;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public class CircuitBreakerInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  private static final String CIRCUIT_BREAKER_FQCN =
      "io.github.resilience4j.circuitbreaker.CircuitBreaker";

  public CircuitBreakerInstrumentation() {
    super("resilience4j", "resilience4j-circuitbreaker");
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
        CircuitBreakerInstrumentation.class.getName() + "$WrapCircuitBreakerAdvice");
    transformer.applyAdvice(
        isMethod()
            .and(isStatic())
            .and(named("decorateCompletionStage"))
            .and(takesArgument(0, named(CIRCUIT_BREAKER_FQCN)))
            .and(takesArgument(1, named("java.util.function.Supplier"))),
        CircuitBreakerInstrumentation.class.getName() + "$CompletionStageAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".DDContext",
      //        packageName + ".CircuitBreakerWithContext", //TODO how to satisfy muzzle check but
      // prevent CircuitBreaker loading at runtime
      // FIXME without this muzzle check fails "Missing class
      // datadog.trace.instrumentation.resilience4j.CircuitBreakerWithContext"
      // but with it, instrumentation fails "java.lang.LinkageError: loader 'app' attempted
      // duplicate interface definition for io.github.resilience4j.circuitbreaker.CircuitBreaker.
      // (io.github.resilience4j.circuitbreaker.CircuitBreaker is in unnamed module of loader
      // 'app')"
      //        "datadog.trace.instrumentation.resilience4j.CircuitBreakerWithContext",
    };
  }

  public static class WrapCircuitBreakerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beforeExecute(
        @Advice.Argument(value = 0, readOnly = false) CircuitBreaker circuitBreaker) {
      circuitBreaker = new CircuitBreakerWithContext(circuitBreaker, new DDContext());
    }
  }

  public static class CompletionStageAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void beforeExecute(
        @Advice.Argument(value = 0, readOnly = false) CircuitBreaker circuitBreaker,
        @Advice.Argument(value = 1, readOnly = false) Supplier<CompletionStage<?>> supplier) {
      DDContext ddContext = new DDContext();
      final Supplier<CompletionStage<?>> delegate = supplier;
      circuitBreaker = new CircuitBreakerWithContext(circuitBreaker, ddContext);
      supplier = DDContext.wrap(delegate, ddContext);
    }
  }
}
