package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.tooling.bytebuddy.matcher.HierarchyMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.undertow.UndertowBlockingHandler.REQUEST_BLOCKING_DATA;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DD_HTTPSERVEREXCHANGE_DISPATCH;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DD_UNDERTOW_SPAN;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.gateway.Flow.Action.RequestBlockingAction;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.undertow.server.HttpServerExchange;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

@AutoService(Instrumenter.class)
public final class HandlerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForTypeHierarchy {

  public HandlerInstrumentation() {
    super("undertow", "undertow-2.0");
  }

  @Override
  public String hierarchyMarkerType() {
    return "io.undertow.server.HttpHandler";
  }

  @Override
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
    return implementsInterface(named(hierarchyMarkerType()));
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("handleRequest"))
            .and(takesArgument(0, named("io.undertow.server.HttpServerExchange")))
            .and(isPublic()),
        getClass().getName() + "$HandlerAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".ExchangeEndSpanListener",
      packageName + ".HttpServerExchangeURIDataAdapter",
      packageName + ".UndertowDecorator",
      packageName + ".UndertowExtractAdapter",
      packageName + ".UndertowExtractAdapter$Request",
      packageName + ".UndertowExtractAdapter$Response",
      packageName + ".UndertowBlockingHandler",
    };
  }

  public static class HandlerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class, skipOn = Advice.OnNonDefaultValue.class)
    public static boolean onEnter(
        @Advice.Argument(value = 0) HttpServerExchange exchange,
        @Advice.Local("agentScope") AgentScope scope) {
      // HttpHandler subclasses are chained so only the first one should create a span
      if (null != exchange.getAttachment(DD_HTTPSERVEREXCHANGE_DISPATCH)) {
        return false;
      }

      final AgentSpan.Context.Extracted extractedContext = DECORATE.extract(exchange);
      final AgentSpan span = DECORATE.startSpan(exchange, extractedContext).setMeasured(true);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, exchange, exchange, extractedContext);

      scope = activateSpan(span);
      scope.setAsyncPropagation(true);

      // For use by servlet instrumentation
      exchange.putAttachment(DD_UNDERTOW_SPAN, span);
      exchange.putAttachment(DD_HTTPSERVEREXCHANGE_DISPATCH, false);

      // TODO is this required?
      // exchange.getRequestHeaders().add(
      //   new HttpString(CorrelationIdentifier.getTraceIdKey()), GlobalTracer.get().getTraceId());
      // exchange.getRequestHeaders().add(
      //   new HttpString(CorrelationIdentifier.getSpanIdKey()), GlobalTracer.get().getSpanId());

      RequestBlockingAction rab = span.getRequestBlockingAction();
      if (rab != null) {
        exchange.putAttachment(REQUEST_BLOCKING_DATA, rab);
        if (exchange.isInIoThread()) {
          exchange.dispatch(UndertowBlockingHandler.INSTANCE);
        } else {
          UndertowBlockingHandler.INSTANCE.handleRequest(exchange);
        }
        return true; /* skip */
      }

      return false;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeScope(
        @Advice.Local("agentScope") final AgentScope scope,
        @Advice.Argument(value = 0) HttpServerExchange exchange,
        @Advice.Thrown final Throwable throwable) {
      if (null != scope) {
        if (null != throwable) {
          DECORATE.onError(scope.span(), throwable);
          exchange.addExchangeCompleteListener(new ExchangeEndSpanListener(scope.span()));
        } else {
          boolean dispatched = exchange.getAttachment(DD_HTTPSERVEREXCHANGE_DISPATCH);
          if (!dispatched) {
            DECORATE.onResponse(scope.span(), exchange);
            DECORATE.beforeFinish(scope.span());
            scope.span().finish();
          }
        }
        scope.close();
      }
    }
  }
}
