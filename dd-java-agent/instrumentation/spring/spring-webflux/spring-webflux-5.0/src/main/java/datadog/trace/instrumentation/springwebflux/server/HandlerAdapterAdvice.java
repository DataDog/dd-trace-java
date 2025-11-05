package datadog.trace.instrumentation.springwebflux.server;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;
import static datadog.trace.instrumentation.springwebflux.server.AdviceUtils.constructOperationName;
import static datadog.trace.instrumentation.springwebflux.server.SpringWebfluxHttpServerDecorator.DECORATE;

import datadog.trace.bootstrap.InstrumentationContext;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import org.reactivestreams.Publisher;
import org.springframework.http.HttpMethod;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.HandlerResult;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.pattern.PathPattern;
import reactor.core.publisher.Mono;

public class HandlerAdapterAdvice {

  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope methodEnter(
      @Advice.Argument(0) final ServerWebExchange exchange,
      @Advice.Argument(1) final Object handler) {

    AgentScope scope = null;
    final AgentSpan span = exchange.getAttribute(AdviceUtils.SPAN_ATTRIBUTE);
    if (handler != null && span != null) {
      final String handlerType;
      final CharSequence operationName;

      if (handler instanceof HandlerMethod) {
        // Special case for requests mapped with annotations
        final HandlerMethod handlerMethod = (HandlerMethod) handler;
        operationName = DECORATE.spanNameForMethod(handlerMethod.getMethod());
        handlerType = handlerMethod.getMethod().getDeclaringClass().getName();
      } else {
        operationName = constructOperationName(handler);
        handlerType = handler.getClass().getName();
      }

      span.setSpanName(operationName);
      span.setTag("handler.type", handlerType);

      scope = activateSpan(span);
    }

    final AgentSpan parentSpan = exchange.getAttribute(AdviceUtils.PARENT_SPAN_ATTRIBUTE);
    final PathPattern bestPattern =
        exchange.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
    if (parentSpan != null
        && bestPattern != null
        && !bestPattern.getPatternString().equals("/**")) {
      final HttpMethod method = exchange.getRequest().getMethod();
      HTTP_RESOURCE_DECORATOR.withRoute(
          parentSpan, method != null ? method.name() : null, bestPattern.getPatternString());
    }

    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void methodExit(
      @Advice.Return(readOnly = false) Mono<HandlerResult> mono,
      @Advice.Argument(0) final ServerWebExchange exchange,
      @Advice.Enter final AgentScope scope,
      @Advice.Thrown final Throwable throwable) {
    if (scope != null) {
      if (throwable != null) {
        DECORATE.onError(scope, throwable);
      } else if (mono != null) {
        InstrumentationContext.get(Publisher.class, AgentSpan.class).put(mono, scope.span());
      }
      scope.close();
      // span finished in SpanFinishingSubscriber
    }
  }
}
