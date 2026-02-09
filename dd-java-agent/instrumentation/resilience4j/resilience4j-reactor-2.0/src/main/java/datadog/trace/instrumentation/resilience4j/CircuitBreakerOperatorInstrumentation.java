package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import datadog.context.Context;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Publisher;

public class CircuitBreakerOperatorInstrumentation
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.reactor.circuitbreaker.operator.CircuitBreakerOperator";
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
        @Advice.Return(readOnly = false) Publisher<?> result,
        @Advice.FieldValue(value = "circuitBreaker") CircuitBreaker circuitBreaker) {

      result =
          ReactorHelper.wrapPublisher(
              result,
              CircuitBreakerDecorator.DECORATE,
              circuitBreaker,
              InstrumentationContext.get(Publisher.class, Context.class)::put);
    }
  }
}
