package datadog.trace.instrumentation.jetty_client12;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jetty_client12.HeadersInjectAdapter.SETTER;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.transport.HttpRequest;

public class SendAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope methodEnter(@Advice.This final HttpRequest request) {
    AgentSpan span = startSpan(JettyClientDecorator.HTTP_REQUEST);
    InstrumentationContext.get(Request.class, AgentSpan.class).put(request, span);
    JettyClientDecorator.DECORATE.afterStart(span);
    JettyClientDecorator.DECORATE.onRequest(span, request);
    propagate().inject(span, request, SETTER);
    propagate()
        .injectPathwayContext(span, request, SETTER, HttpClientDecorator.CLIENT_PATHWAY_EDGE_TAGS);
    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void methodExit(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    try (scope) {
      if (throwable != null) {
        JettyClientDecorator.DECORATE.onError(scope, throwable);
        JettyClientDecorator.DECORATE.beforeFinish(scope);
        scope.span().finish();
      }
    }
  }
}
