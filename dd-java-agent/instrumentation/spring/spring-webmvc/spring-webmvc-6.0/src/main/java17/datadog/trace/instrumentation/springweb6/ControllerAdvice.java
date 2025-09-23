package datadog.trace.instrumentation.springweb6;

import static datadog.context.Context.root;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.springweb6.SpringWebHttpServerDecorator.DD_HANDLER_SPAN_CONTINUE_SUFFIX;
import static datadog.trace.instrumentation.springweb6.SpringWebHttpServerDecorator.DD_HANDLER_SPAN_PREFIX_KEY;
import static datadog.trace.instrumentation.springweb6.SpringWebHttpServerDecorator.DECORATE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import org.springframework.web.method.HandlerMethod;

public class ControllerAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope nameResourceAndStartSpan(
      @Advice.Argument(0) final HttpServletRequest request,
      @Advice.Argument(2) final Object handler,
      @Advice.Local("handlerSpanKey") String handlerSpanKey) {
    handlerSpanKey = "";
    // Name the parent span based on the matching pattern
    Object parentSpan = request.getAttribute(DD_SPAN_ATTRIBUTE);
    if (parentSpan instanceof AgentSpan) {
      DECORATE.onRequest((AgentSpan) parentSpan, request, request, root());
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
    final Object existingSpan = request.getAttribute(handlerSpanKey);
    if (existingSpan instanceof AgentSpan) {
      return activateSpan((AgentSpan) existingSpan);
    }

    final AgentSpan span = startSpan(DECORATE.spanName()).setMeasured(true);
    DECORATE.afterStart(span);
    DECORATE.onHandle(span, handler);
    request.setAttribute(handlerSpanKey, span);
    return activateSpan(span);
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final AgentScope scope,
      @Advice.Argument(0) final HttpServletRequest request,
      @Advice.Thrown final Throwable throwable,
      @Advice.Local("handlerSpanKey") String handlerSpanKey) {
    if (scope == null) {
      return;
    }
    boolean finish =
        !Boolean.TRUE.equals(
            request.getAttribute(handlerSpanKey + DD_HANDLER_SPAN_CONTINUE_SUFFIX));
    final AgentSpan span = scope.span();
    scope.close();
    if (throwable != null) {
      DECORATE.onError(span, throwable);
      finish = true;
    }
    if (finish) {
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
