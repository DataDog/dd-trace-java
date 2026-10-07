package datadog.trace.instrumentation.jetty_client12;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromScope;
import static datadog.trace.instrumentation.jetty_client12.JettyClientDecorator.DECORATE;
import static datadog.trace.instrumentation.jetty_client12.JettyClientDecorator.HTTP_REQUEST;

import datadog.context.ContextScope;
import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.transport.HttpRequest;

public class SendAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ContextScope methodEnter(@Advice.This final HttpRequest request) {
    AgentSpan span = startSpan("jetty-client", HTTP_REQUEST);
    InstrumentationContext.get(Request.class, AgentSpan.class).put(request, span);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request);
    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void methodExit(
      @Advice.Enter final ContextScope scope, @Advice.Thrown final Throwable throwable) {
    try (scope) {
      if (throwable != null) {
        DECORATE.onError(scope, throwable);
        DECORATE.beforeFinish(scope);
        spanFromScope(scope).finish();
      }
    }
  }
}
