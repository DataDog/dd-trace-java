package datadog.trace.instrumentation.springweb6;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;

public class ControllerAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope nameResourceAndStartSpan(
      @Advice.Argument(0) final HttpServletRequest request,
      @Advice.Argument(2) final Object handler) {
    // Name the parent span based on the matching pattern
    Object parentSpan = request.getAttribute(DD_SPAN_ATTRIBUTE);
    if (parentSpan instanceof AgentSpan) {
      SpringWebHttpServerDecorator.DECORATE.onRequest(
          (AgentSpan) parentSpan, request, request, null);
    }

    if (activeSpan() == null) {
      return null;
    }

    // Now create a span for handler/controller execution.

    final AgentSpan span =
        startSpan(SpringWebHttpServerDecorator.DECORATE.spanName()).setMeasured(true);
    SpringWebHttpServerDecorator.DECORATE.afterStart(span);
    SpringWebHttpServerDecorator.DECORATE.onHandle(span, handler);

    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);
    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void stopSpan(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    if (scope == null) {
      return;
    }

    SpringWebHttpServerDecorator.DECORATE.onError(scope, throwable);
    SpringWebHttpServerDecorator.DECORATE.beforeFinish(scope);
    scope.close();
    scope.span().finish();
  }
}
