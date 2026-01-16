package datadog.trace.instrumentation.jetty10;

import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty10.JettyDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;

public class HandleAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ContextScope onEnter(
      @Advice.This final HttpChannel channel, @Advice.Local("agentSpan") AgentSpan span) {
    Request req = channel.getRequest();

    Object existingContext = req.getAttribute(DD_CONTEXT_ATTRIBUTE);
    if (existingContext instanceof Context) {
      return ((Context) existingContext).attach();
    }

    final Context parentContext = DECORATE.extract(req);
    final Context context = DECORATE.startSpan(req, parentContext);
    span = spanFromContext(context);
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, req, req, parentContext);

    final ContextScope scope = context.attach();
    req.setAttribute(DD_CONTEXT_ATTRIBUTE, context);
    req.setAttribute(CorrelationIdentifier.getTraceIdKey(), CorrelationIdentifier.getTraceId());
    req.setAttribute(CorrelationIdentifier.getSpanIdKey(), CorrelationIdentifier.getSpanId());
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
