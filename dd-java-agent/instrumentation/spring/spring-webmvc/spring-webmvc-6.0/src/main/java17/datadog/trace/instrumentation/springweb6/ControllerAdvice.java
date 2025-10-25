package datadog.trace.instrumentation.springweb6;

import static datadog.context.Context.root;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.springweb6.SpringWebHttpServerDecorator.DD_HANDLER_SPAN_CONTINUE_SUFFIX;
import static datadog.trace.instrumentation.springweb6.SpringWebHttpServerDecorator.DD_HANDLER_SPAN_PREFIX_KEY;
import static datadog.trace.instrumentation.springweb6.SpringWebHttpServerDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import org.springframework.web.method.HandlerMethod;

public class ControllerAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ContextScope nameResourceAndStartSpan(
      @Advice.Argument(0) final HttpServletRequest request,
      @Advice.Argument(2) final Object handler,
      @Advice.Local("handlerSpanKey") String handlerSpanKey) {
    handlerSpanKey = "";
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

    final String handlerKey;
    if (handler instanceof HandlerMethod) {
      handlerKey = ((HandlerMethod) handler).getBean().getClass().getName();
    } else {
      handlerKey = handler.getClass().getName();
    }
    handlerSpanKey = DD_HANDLER_SPAN_PREFIX_KEY + handlerKey;

    // If the context already exists, return it
    final Object existingContext = request.getAttribute(handlerSpanKey);
    if (existingContext instanceof Context) {
      return ((Context) existingContext).attach();
    }

    final AgentSpan span = startSpan(DECORATE.spanName()).setMeasured(true);
    DECORATE.afterStart(span);
    DECORATE.onHandle(span, handler);

    request.setAttribute(handlerSpanKey, span);
    return span.attach();
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final ContextScope scope,
      @Advice.Argument(0) final HttpServletRequest request,
      @Advice.Thrown final Throwable throwable,
      @Advice.Local("handlerSpanKey") String handlerSpanKey) {
    if (scope == null) {
      return;
    }
    boolean finish =
        !Boolean.TRUE.equals(
            request.getAttribute(handlerSpanKey + DD_HANDLER_SPAN_CONTINUE_SUFFIX));
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
