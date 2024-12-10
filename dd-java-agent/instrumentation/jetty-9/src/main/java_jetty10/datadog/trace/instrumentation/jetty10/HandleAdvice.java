package datadog.trace.instrumentation.jetty10;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty10.JettyDecorator.DECORATE;

import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.GlobalTracer;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;

public class HandleAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope onEnter(
      @Advice.This final HttpChannel channel, @Advice.Local("agentSpan") AgentSpan span) {
    Request req = channel.getRequest();

    Object existingSpan = req.getAttribute(DD_SPAN_ATTRIBUTE);
    if (existingSpan instanceof AgentSpan) {
      return activateSpan((AgentSpan) existingSpan);
    }

    final AgentSpan.Context.Extracted extractedContext = DECORATE.extract(req);
    span = DECORATE.startSpan(req, extractedContext);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, req, req, extractedContext);

    final AgentScope scope = activateSpan(span, true);
    req.setAttribute(DD_SPAN_ATTRIBUTE, span);
    req.setAttribute(CorrelationIdentifier.getTraceIdKey(), GlobalTracer.get().getTraceId());
    req.setAttribute(CorrelationIdentifier.getSpanIdKey(), GlobalTracer.get().getSpanId());
    return scope;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void closeScope(@Advice.Enter final AgentScope scope) {
    scope.close();
  }

  private void muzzleCheck(Request r) {
    r.getAsyncContext(); // there must be a getAsyncContext returning a javax AsyncContext
  }
}
