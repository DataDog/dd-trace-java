package datadog.trace.instrumentation.jetty_client10;

import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.CONTEXT_TRACKING;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.instrumentation.jetty_client.HeadersInjectAdapter.SETTER;
import static datadog.trace.instrumentation.jetty_client10.JettyClientDecorator.DECORATE;

import datadog.trace.agent.tooling.annotation.AppliesOn;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.client.api.Request;

@AppliesOn(CONTEXT_TRACKING)
public class SendContextPropagationAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void methodEnter(@Advice.Argument(0) final Request request) {
    DECORATE.injectContext(getCurrentContext(), request, SETTER);
  }
}
