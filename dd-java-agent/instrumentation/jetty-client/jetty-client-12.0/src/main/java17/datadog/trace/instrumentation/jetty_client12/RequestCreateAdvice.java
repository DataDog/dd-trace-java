package datadog.trace.instrumentation.jetty_client12;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.transport.HttpRequest;

public class RequestCreateAdvice {
  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void afterCreate(@Advice.This HttpRequest self) {
    self.onComplete(
        new SpanFinishingCompleteListener(
            InstrumentationContext.get(Request.class, AgentSpan.class)));
  }
}
