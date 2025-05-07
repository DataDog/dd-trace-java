package datadog.trace.instrumentation.jetty_client12;

import static datadog.context.propagation.Propagators.defaultPropagator;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.instrumentation.jetty_client12.HeadersInjectAdapter.SETTER;
import static datadog.trace.instrumentation.jetty_client12.JettyClientDecorator.HTTP_REQUEST;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.client.Request;
import org.eclipse.jetty.client.transport.HttpRequest;

public class SendAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope methodEnter(@Advice.This final HttpRequest request) {
    AgentSpan span = startSpan("jetty-client", HTTP_REQUEST);
    InstrumentationContext.get(Request.class, AgentSpan.class).put(request, span);
    JettyClientDecorator.DECORATE.afterStart(span);
    JettyClientDecorator.DECORATE.onRequest(span, request);
    defaultPropagator().inject(getCurrentContext().with(span), request, SETTER);
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
