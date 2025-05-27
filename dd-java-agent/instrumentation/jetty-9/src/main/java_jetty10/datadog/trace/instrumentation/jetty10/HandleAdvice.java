package datadog.trace.instrumentation.jetty10;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty10.JettyDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;

public class HandleAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ContextScope onEnter(
      @Advice.This final HttpChannel channel, @Advice.Local("agentSpan") AgentSpan span) {
    Request req = channel.getRequest();

    Object existingSpan = req.getAttribute(DD_SPAN_ATTRIBUTE);
    if (existingSpan instanceof AgentSpan) {
      return ((AgentSpan) existingSpan).attach();
    }

    final Context extractedContext = DECORATE.extractContext(req);
    span = DECORATE.startSpan(req, extractedContext);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, req, req, extractedContext);

    final ContextScope scope = extractedContext.with(span).attach();
    req.setAttribute(DD_SPAN_ATTRIBUTE, span);
    req.setAttribute(CorrelationIdentifier.getTraceIdKey(), GlobalTracer.get().getTraceId());
    req.setAttribute(CorrelationIdentifier.getSpanIdKey(), GlobalTracer.get().getSpanId());
    return scope;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void closeScope(@Advice.Enter final ContextScope scope) {
    scope.close();
  }

  private void muzzleCheck(Request r) {
    r.getAsyncContext(); // there must be a getAsyncContext returning a javax AsyncContext
  }
}
