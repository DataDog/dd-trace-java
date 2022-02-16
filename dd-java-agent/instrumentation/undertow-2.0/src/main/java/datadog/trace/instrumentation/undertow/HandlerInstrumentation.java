package datadog.trace.instrumentation.undertow;

import com.google.auto.service.AutoService;

import datadog.trace.api.CorrelationIdentifier;
import datadog.trace.api.GlobalTracer;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.HeaderMap;
import io.undertow.util.HttpString;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeScope;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DD_HTTPSERVEREXCHANGE_DISPATCH;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DD_UNDERTOW_SPAN;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

@AutoService(Instrumenter.class)
public final class HandlerInstrumentation extends Instrumenter.Tracing {

  public HandlerInstrumentation() {
    super("undertow", "undertow-2.0");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("io.undertow.server.HttpHandler"));
  }

  @Override
  public ElementMatcher<ClassLoader> classLoaderMatcher() {
    return hasClassesNamed("io.undertow.server.HttpServerExchange");
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
    return new String[]{
        packageName + ".ExchangeEndSpanListener",
        packageName + ".HttpServerExchangeURIDataAdapter",
        packageName + ".UndertowDecorator",
        packageName + ".UndertowExtractAdapter",
        packageName + ".UndertowExtractAdapter$Request",
        packageName + ".UndertowExtractAdapter$Response"
    };
  }

  // @Advice.Argument(value = 0, readOnly = false) HttpServerExchange exchange
  public static class HandlerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(value = 0) HttpServerExchange exchange,
        @Advice.This HttpHandler handler) {
      // HttpHandler subclasses are chained to only the first should create a span
      if (null != exchange.getAttachment(DD_HTTPSERVEREXCHANGE_DISPATCH)) {
        return null;
      }

      final AgentSpan.Context.Extracted extractedContext = DECORATE.extract(exchange);
      final AgentSpan span = DECORATE.startSpan(exchange, extractedContext).setMeasured(true);
      DECORATE.afterStart(span);
      // TODO CRG should this be delayed until we know if the servlet instrumentation is being invoked?
      // This could be called in closeScope if the scope is not null and DD_SPAN_ATTRIBUTE is NULL
      DECORATE.onRequest(span, exchange, exchange, extractedContext);

      final AgentScope scope = activateSpan(span);
      scope.setAsyncPropagation(true);

      // For use by servlet instrumentation
      exchange.putAttachment(DD_UNDERTOW_SPAN, span);
      exchange.putAttachment(DD_HTTPSERVEREXCHANGE_DISPATCH, false);
      exchange.addExchangeCompleteListener(new ExchangeEndSpanListener(span));

      // TODO CRG is this required.. only some servers seem to do it.. seems related to logs
      // exchange.getRequestHeaders().add(
      //   new HttpString(CorrelationIdentifier.getTraceIdKey()), GlobalTracer.get().getTraceId());
      // exchange.getRequestHeaders().add(
      //   new HttpString(CorrelationIdentifier.getSpanIdKey()), GlobalTracer.get().getSpanId());
       
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeScope(
      @Advice.Enter final AgentScope scope,
      @Advice.Argument(value = 0) HttpServerExchange exchange,
      @Advice.Thrown final Throwable throwable) {
      if (null != scope) {
        if (null != throwable) {
          // end exchange will be called after setting response code / exception
          exchange.putAttachment(DD_HTTPSERVEREXCHANGE_DISPATCH, true);
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
