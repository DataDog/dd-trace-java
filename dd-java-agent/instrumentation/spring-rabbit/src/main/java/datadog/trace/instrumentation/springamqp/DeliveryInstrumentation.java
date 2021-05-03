package datadog.trace.instrumentation.springamqp;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import datadog.trace.context.TraceScope;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.springframework.amqp.rabbit.support.Delivery;

@AutoService(Instrumenter.class)
public class DeliveryInstrumentation extends Instrumenter.Tracing {
  public DeliveryInstrumentation() {
    super("spring-rabbit");
  }

  @Override
  public ElementMatcher<? super TypeDescription> typeMatcher() {
    return named("org.springframework.amqp.rabbit.support.Delivery");
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(isConstructor(), getClass().getName() + "$CaptureActiveScope");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.springframework.amqp.rabbit.support.Delivery", State.class.getName());
  }

  public static class CaptureActiveScope {
    @Advice.OnMethodExit
    public static void captureActiveScope(@Advice.This Delivery delivery) {
      TraceScope scope = activeScope();
      if (null != scope) {
        State state = State.FACTORY.create();
        state.captureAndSetContinuation(scope);
        InstrumentationContext.get(Delivery.class, State.class).put(delivery, state);
      }
    }
  }
}
