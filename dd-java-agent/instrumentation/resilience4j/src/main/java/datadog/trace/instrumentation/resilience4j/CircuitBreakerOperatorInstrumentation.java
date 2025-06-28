package datadog.trace.instrumentation.resilience4j;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import net.bytebuddy.asm.Advice;
import reactor.core.CoreSubscriber;

@AutoService(InstrumenterModule.class)
public class CircuitBreakerOperatorInstrumentation extends AbstractResilience4jInstrumentation {

  public CircuitBreakerOperatorInstrumentation() {
    super("resilience4j-circuitbreaker");
  }

  @Override
  public String instrumentedType() {
    return "io.github.resilience4j.reactor.circuitbreaker.operator.FluxCircuitBreaker";
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".CompleteSpan",
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(
        isMethod()
            .and(named("subscribe")) // TODO CorePublisherInstrumentation instruments the subscribe
            // method to activate the propagated parent context. How do we
            // guarantee the this instrumentation is done first, so it's
            // properly wrapped by the subscribe instrumentations
            .and(takesArgument(0, named("reactor.core.CoreSubscriber"))),
        CircuitBreakerOperatorInstrumentation.class.getName() + "$SubscribeAdvice");
  }

  public static class SubscribeAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope enter(
        @Advice.Argument(value = 0, readOnly = false) CoreSubscriber<?> actual) {
      // TODO check if resilience4j span already active
      AgentSpan span = AgentTracer.startSpan("resilience4j", "resilience4j");

      AgentScope scope = AgentTracer.activateSpan(span);
      actual = new CompleteSpan<>(actual, span);
      return scope;
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void after(@Advice.Enter final AgentScope scope) {
      if (scope != null) {
        scope.close();
      }
    }
  }
}
