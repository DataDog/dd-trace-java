package datadog.trace.instrumentation.jetty10;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_DISPATCH_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.jetty10.JettyDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.eclipse.jetty.server.HttpChannel;
import org.eclipse.jetty.server.Request;

class ServerHandleAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  static AgentScope onEnter(
      @Advice.Argument(0) HttpChannel channel,
      @Advice.Local("request") Request req,
      @Advice.Local("agentSpan") AgentSpan span) {
    req = channel.getRequest();

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
      // the root span, stored in DD_SPAN_ATTRIBUTE.
      // req.removeAttribute(DD_DISPATCH_SPAN_ATTRIBUTE);
      span = (AgentSpan) dispatchSpan;
      AgentScope scope = activateSpan(span);
      return scope;
    }

    return null;
  }

  @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
  public static void onExit(
      @Advice.Enter final AgentScope scope,
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
      DECORATE.beforeFinish(span);
      span.finish();
    }
    scope.close();

    synchronized (req) {
      req.removeAttribute(DD_DISPATCH_SPAN_ATTRIBUTE);
    }
  }
}
