package datadog.trace.instrumentation.springweb6;

import static datadog.context.Context.root;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_CONTEXT_ATTRIBUTE;
import static datadog.trace.instrumentation.springweb6.SpringWebHttpServerDecorator.DECORATE;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.servlet.http.HttpServletRequest;
import net.bytebuddy.asm.Advice;
import org.springframework.web.servlet.HandlerExecutionChain;

/**
 * Names the server span route as soon as {@code DispatcherServlet} has resolved the handler.
 *
 * <p>For {@code RequestMappingInfoHandlerMapping}, {@code handleMatch} sets {@link
 * org.springframework.web.servlet.HandlerMapping#BEST_MATCHING_PATTERN_ATTRIBUTE} synchronously
 * inside {@code getHandler}, before interceptors run. Naming the route here means it survives a
 * {@code HandlerInterceptor.preHandle} that aborts the request before the controller executes.
 */
public class HandlerMappingAdvice {

  @Advice.OnMethodExit(suppress = Throwable.class)
  public static void onExit(
      @Advice.Argument(0) final HttpServletRequest request,
      @Advice.Return final HandlerExecutionChain chain) {
    if (chain == null) {
      // No handler matched (e.g. 404): leave the resource name untouched.
      return;
    }
    final Object contextObj = request.getAttribute(DD_CONTEXT_ATTRIBUTE);
    if (contextObj instanceof Context) {
      final AgentSpan parentSpan = spanFromContext((Context) contextObj);
      if (parentSpan != null) {
        DECORATE.onRequest(parentSpan, request, request, root());
      }
    }
  }
}
