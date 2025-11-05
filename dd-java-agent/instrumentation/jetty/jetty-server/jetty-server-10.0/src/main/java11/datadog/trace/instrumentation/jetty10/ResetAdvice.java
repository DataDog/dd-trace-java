package datadog.trace.instrumentation.jetty10;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty10.JettyDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;

/**
 * Jetty ensures that connections are reset immediately after the response is sent. This provides a
 * reliable point to finish the server span at the last possible moment.
 */
public class ResetAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static void stopSpan(@Advice.This final HttpChannel channel) {
    Request req = channel.getRequest();
    Object spanObj = req.getAttribute(DD_SPAN_ATTRIBUTE);
    if (spanObj instanceof AgentSpan) {
      final AgentSpan span = (AgentSpan) spanObj;
      JettyDecorator.OnResponse.onResponse(span, channel);
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
