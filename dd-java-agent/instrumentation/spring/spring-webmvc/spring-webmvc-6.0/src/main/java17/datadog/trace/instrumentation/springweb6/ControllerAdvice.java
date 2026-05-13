package datadog.trace.instrumentation.springweb6;

import static datadog.context.Context.root;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.springweb6.SpringWebHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.springweb6.SpringWebHttpServerDecorator.handlerSpanKeysFor;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.Pair;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;

public class ControllerAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ContextScope nameResourceAndStartSpan(
      @Advice.Argument(0) final HttpServletRequest request,
      @Advice.Argument(2) final Object handler,
      @Advice.Local("handlerSpanKeys") Pair<String, String> handlerSpanKeys) {

    // Name the parent span based on the matching pattern
    Object contextObj = request.getAttribute(DD_CONTEXT_ATTRIBUTE);
    if (contextObj instanceof Context) {
      Context context = (Context) contextObj;
      AgentSpan parentSpan = spanFromContext(context);
      if (parentSpan != null) {
        DECORATE.onRequest(parentSpan, request, request, root());
      }
    }

    if (activeSpan() == null) {
      return null;
    }

    // Now create a span for handler/controller execution.
    handlerSpanKeys = handlerSpanKeysFor(handler);

    // If the context already exists, return it
    final Object existingContext = request.getAttribute(handlerSpanKeys.getLeft());
    if (existingContext instanceof Context) {
      return ((Context) existingContext).attach();
    }

    final AgentSpan span =
        startSpan("spring-web-controller", DECORATE.spanName()).setMeasured(true);
    DECORATE.afterStart(span);
    DECORATE.onHandle(span, handler);

    request.setAttribute(handlerSpanKeys.getLeft(), span);
    return getCurrentContext().with(span).attach();
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final ContextScope scope,
      @Advice.Argument(0) final HttpServletRequest request,
      @Advice.Thrown final Throwable throwable,
      @Advice.Local("handlerSpanKeys") Pair<String, String> handlerSpanKeys) {
    if (scope == null) {
      return;
    }
    boolean finish =
        handlerSpanKeys != null
            && !Boolean.TRUE.equals(request.getAttribute(handlerSpanKeys.getRight()));
    final AgentSpan span = spanFromContext(scope.context());
    scope.close();
    if (throwable != null) {
      DECORATE.onError(span, throwable);
      finish = true;
    }
    if (finish) {
      DECORATE.beforeFinish(scope.context());
      span.finish();
    }
  }
}
