package datadog.trace.instrumentation.springweb7;

import static datadog.context.Context.root;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.springweb7.SpringWebHttpServerDecorator.DD_HANDLER_SPAN_CONTINUE_SUFFIX;
import static datadog.trace.instrumentation.springweb7.SpringWebHttpServerDecorator.DD_HANDLER_SPAN_PREFIX_KEY;
import static datadog.trace.instrumentation.springweb7.SpringWebHttpServerDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import org.springframework.web.method.HandlerMethod;

/**
 * By the time {@code HandlerAdapter.handle} runs, every handler mapping kind (annotated and {@code
 * SimpleUrlHandlerMapping} via its {@code PathExposingHandlerInterceptor}) has populated {@code
 * BEST_MATCHING_PATTERN_ATTRIBUTE}.
 */
public class ControllerAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ContextScope nameResourceAndStartSpan(
      @Advice.Argument(0) final HttpServletRequest request,
      @Advice.Argument(2) final Object handler,
      @Advice.Local("handlerSpanKey") String handlerSpanKey) {
    handlerSpanKey = "";

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

    final String handlerKey;
    if (handler instanceof HandlerMethod) {
      handlerKey = ((HandlerMethod) handler).getBean().getClass().getName();
    } else {
      handlerKey = handler.getClass().getName();
    }
    handlerSpanKey = DD_HANDLER_SPAN_PREFIX_KEY + handlerKey;

    final Object existingContext = request.getAttribute(handlerSpanKey);
    if (existingContext instanceof Context) {
      return ((Context) existingContext).attach();
    }

    final AgentSpan span =
        startSpan("spring-web-controller", DECORATE.spanName()).setMeasured(true);
    DECORATE.afterStart(span);
    DECORATE.onHandle(span, handler);

    request.setAttribute(handlerSpanKey, span);
    return span.attachWithContext();
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
