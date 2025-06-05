package datadog.trace.instrumentation.springamqp;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static java.util.Collections.singletonMap;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.java.concurrent.State;
import java.util.Map;
import net.bytebuddy.asm.Advice;
import org.springframework.amqp.rabbit.support.Delivery;

@AutoService(InstrumenterModule.class)
public class DeliveryInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForSingleType, Instrumenter.HasMethodAdvice {
  public DeliveryInstrumentation() {
    super("spring-rabbit");
  }

  @Override
  public String instrumentedType() {
    return "org.springframework.amqp.rabbit.support.Delivery";
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvice(isConstructor(), getClass().getName() + "$CaptureActiveScope");
  }

  @Override
  public Map<String, String> contextStore() {
    return singletonMap("org.springframework.amqp.rabbit.support.Delivery", State.class.getName());
  }

  public static class CaptureActiveScope {
    @Advice.OnMethodExit
    public static void captureActiveScope(@Advice.This Delivery delivery) {
      AgentSpan span = activeSpan();
      if (span != null) {
        State state = State.FACTORY.create();
        state.captureAndSetContinuation(span);
        InstrumentationContext.get(Delivery.class, State.class).put(delivery, state);
      }
    }
  }
}
