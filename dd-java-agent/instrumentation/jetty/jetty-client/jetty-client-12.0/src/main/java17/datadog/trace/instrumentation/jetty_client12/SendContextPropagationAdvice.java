package datadog.trace.instrumentation.jetty_client12;

import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.CONTEXT_TRACKING;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.instrumentation.jetty_client12.HeadersInjectAdapter.SETTER;
import static datadog.trace.instrumentation.jetty_client12.JettyClientDecorator.DECORATE;

import datadog.trace.agent.tooling.annotation.AppliesOn;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.transport.HttpRequest;

@AppliesOn(CONTEXT_TRACKING)
public class SendContextPropagationAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void methodEnter(@Advice.This final HttpRequest request) {
    AgentSpan span = InstrumentationContext.get(Request.class, AgentSpan.class).get(request);
    if (span == null) {
      return;
    }
    DECORATE.injectContext(getCurrentContext().with(span), request, SETTER);
  }
}
