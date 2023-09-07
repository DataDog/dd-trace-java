package datadog.trace.instrumentation.opentelemetry.annotations;

import static datadog.trace.instrumentation.opentelemetry.annotations.WithSpanDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import java.lang.reflect.Method;
import net.bytebuddy.asm.Advice;

public class AddingSpanAttributesAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void onEnter(
      @Advice.Origin final Method method, @Advice.AllArguments final Object[] args) {
    AgentSpan activeSpan = AgentTracer.get().activeSpan();
    if (activeSpan != null) {
      DECORATE.addTagsFromMethodArgs(activeSpan, method, args);
    }
  }
}
