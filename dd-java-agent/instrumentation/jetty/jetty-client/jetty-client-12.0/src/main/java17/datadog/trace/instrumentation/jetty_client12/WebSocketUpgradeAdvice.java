package datadog.trace.instrumentation.jetty_client12;

import static datadog.trace.instrumentation.jetty_client12.JettyClientDecorator.DECORATE;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.Response;

public class WebSocketUpgradeAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterUpgrade(@Advice.Argument(0) Response response) {
    AgentSpan span =
        InstrumentationContext.get(Request.class, AgentSpan.class).get(response.getRequest());
    if (span != null) {
      // Successful upgrades bypass the request's response completion listeners.
      DECORATE.onResponse(span, response);
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
