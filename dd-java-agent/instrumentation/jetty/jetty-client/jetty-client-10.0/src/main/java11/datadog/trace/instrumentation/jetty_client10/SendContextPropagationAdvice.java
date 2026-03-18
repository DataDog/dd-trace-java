package datadog.trace.instrumentation.jetty_client10;

import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.CONTEXT_TRACKING;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.instrumentation.jetty_client.HeadersInjectAdapter.SETTER;
import static datadog.trace.instrumentation.jetty_client10.JettyClientDecorator.DECORATE;

import datadog.context.Context;
import datadog.trace.agent.tooling.annotation.AppliesOn;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.client.api.Request;

@AppliesOn(CONTEXT_TRACKING)
public class SendContextPropagationAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void methodEnter(@Advice.Argument(0) final Request request) {
    final AgentSpan span = InstrumentationContext.get(Request.class, AgentSpan.class).get(request);
    Context destination = getCurrentContext();
    if (span != null) {
      destination = destination.with(span);
    }
    DECORATE.injectContext(destination, request, SETTER);
  }
}
