package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.util.ArrayList;
import java.util.Arrays;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import reactor.core.publisher.Flux;

@AutoService(InstrumenterModule.class)
public class CircuitBreakerOperatorInstrumentation extends AbstractResilience4jInstrumentation {

  public CircuitBreakerOperatorInstrumentation() {
    super("resilience4j-circuitbreaker", "resilience4j-reactor");
  }

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator";
  }

  @Override
  public String[] helperClassNames() {
    ArrayList<String> ret = new ArrayList<>();

    ret.add(packageName + ".ReactorHelper");

    ret.addAll(Arrays.asList(super.helperClassNames()));

    return ret.toArray(new String[0]);
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("apply"))
            .and(takesArgument(0, named("org.reactivestreams.Publisher"))),
        CircuitBreakerOperatorInstrumentation.class.getName() + "$ApplyAdvice");
  }

  public static class ApplyAdvice {
    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(
        @Advice.Return(typing = Assigner.Typing.DYNAMIC, readOnly = false) Object result,
        @Advice.FieldValue(value = "circuitBreaker") CircuitBreaker circuitBreaker) {

      if (result instanceof Flux) {
        System.err.println(
            ">>> (3) CircuitBreakerOperatorInstrumentation enter: circuitBreaker="
                + circuitBreaker);

        // TODO pass decorator into it along with the circuit breaker
        result =
            ReactorHelper.wrapFlux(
                (Flux<?>) result, CircuitBreakerDecorator.DECORATE, circuitBreaker);
      } // TODO mono
    }
  }
}
