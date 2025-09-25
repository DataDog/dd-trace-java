package datadog.trace.instrumentation.jetty_client12;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import org.eclipse.jetty.client.Request;

public class WrapListenerAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void methodEnter(
      @Advice.This Request request,
      @Advice.Argument(value = 0, readOnly = false, typing = Assigner.Typing.DYNAMIC)
          Object listener) {
    if (!(listener instanceof CallbackWrapper)) {
      listener =
          new CallbackWrapper(
              activeSpan(),
              InstrumentationContext.get(Request.class, AgentSpan.class).get(request),
              listener);
    }
  }
}
