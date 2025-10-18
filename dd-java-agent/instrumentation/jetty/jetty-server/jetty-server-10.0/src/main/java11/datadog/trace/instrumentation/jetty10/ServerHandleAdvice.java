package datadog.trace.instrumentation.jetty10;

import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_DISPATCH_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty10.JettyDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;

class ServerHandleAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  static ContextScope onEnter(
      @Advice.Argument(0) HttpChannel channel,
      @Advice.Local("request") Request req,
      @Advice.Local("agentSpan") AgentSpan span) {
    req = channel.getRequest();

    // First check if there's an existing context in the request (from main server span)
    Object existingContext = req.getAttribute(DD_CONTEXT_ATTRIBUTE);

    // same logic as in Servlet3Advice. We need to activate/finish the dispatch span here
    // because we don't know if a servlet is going to be called and therefore whether
    // Servlet3Advice will have an opportunity to run.
    // If there is no servlet involved, the span would not be finished.
    Object dispatchSpan;
    synchronized (req) {
      dispatchSpan = req.getAttribute(DD_DISPATCH_SPAN_ATTRIBUTE);
    }
    if (dispatchSpan instanceof AgentSpan) {
      // this is not great, but we leave the attribute. This is because
      // Set{Servlet,Context}PathAdvice
      // looks for this attribute, and we need a way to tell Servlet3Advice not to activate
      // the root span, stored in DD_CONTEXT_ATTRIBUTE.
      // req.removeAttribute(DD_DISPATCH_SPAN_ATTRIBUTE);
      span = (AgentSpan) dispatchSpan;

      // If we have an existing context, create a new context with the dispatch span
      // Otherwise just attach the dispatch span
      if (existingContext instanceof Context) {
        Context contextWithDispatchSpan = ((Context) existingContext).with(span);
        return contextWithDispatchSpan.attach();
      } else {
        return span.attach();
      }
    }

    return null;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void onExit(
      @Advice.Enter final ContextScope scope,
      @Advice.Local("request") Request req,
      @Advice.Local("agentSpan") AgentSpan span,
      @Advice.Thrown Throwable t) {
    if (scope == null) {
      return;
    }

    if (t != null) {
      DECORATE.onError(span, t);
    }
    if (!req.isAsyncStarted()) {
      // finish will be handled by the async listener
      // Use the full context from the scope for beforeFinish
      DECORATE.beforeFinish(scope.context());
      span.finish();
    }
    scope.close();

    synchronized (req) {
      req.removeAttribute(DD_DISPATCH_SPAN_ATTRIBUTE);
    }
  }
}
