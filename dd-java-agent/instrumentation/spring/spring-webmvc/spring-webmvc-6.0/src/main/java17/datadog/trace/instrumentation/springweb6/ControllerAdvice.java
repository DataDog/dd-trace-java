package datadog.trace.instrumentation.springweb6;

import static datadog.context.Context.root;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.springweb6.SpringWebHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.springweb6.SpringWebHttpServerDecorator.handlerSpanKeys;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.api.Pair;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import org.springframework.web.method.HandlerMethod;

public class ControllerAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ContextScope nameResourceAndStartSpan(
      @Advice.Argument(0) final HttpServletRequest request,
      @Advice.Argument(2) final Object handler,
      @Advice.Local("handlerSpanKeys") Pair<String, String> handlerSpanKeys) {

    /*
    By the time HandlerAdapter.handle runs, every handler mapping kind (annotated and SimpleUrlHandlerMapping via its
    PathExposingHandlerInterceptor) has populated BEST_MATCHING_PATTERN_ATTRIBUTE.
    */
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

    final Class<?> handlerClass;
    if (handler instanceof HandlerMethod) {
      handlerClass = ((HandlerMethod) handler).getBean().getClass();
    } else {
      handlerClass = handler.getClass();
    }
    handlerSpanKeys = handlerSpanKeys(handlerClass);

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
    return span.attachWithContext();
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
    boolean finish = !Boolean.TRUE.equals(request.getAttribute(handlerSpanKeys.getRight()));
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
