package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.tooling.ClassLoaderMatcher.hasClassesNamed;
import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DD_HTTPSERVEREXCHANGE_DISPATCH;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DD_UNDERTOW_SPAN;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.undertow.server.HttpHandler;
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
  public ElementMatcher<TypeDescription> hierarchyMatcher() {
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
    return new String[] {
      packageName + ".ExchangeEndSpanListener",
      packageName + ".HttpServerExchangeURIDataAdapter",
      packageName + ".UndertowDecorator",
      packageName + ".UndertowExtractAdapter",
      packageName + ".UndertowExtractAdapter$Request",
      packageName + ".UndertowExtractAdapter$Response"
    };
  }

  public static class HandlerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(value = 0) HttpServerExchange exchange, @Advice.This HttpHandler handler) {
      // HttpHandler subclasses are chained so only the first one should create a span
      if (null != exchange.getAttachment(DD_HTTPSERVEREXCHANGE_DISPATCH)) {
        return null;
      }

      final AgentSpan.Context.Extracted extractedContext = DECORATE.extract(exchange);
      final AgentSpan span = DECORATE.startSpan(exchange, extractedContext).setMeasured(true);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, exchange, exchange, extractedContext);

      final AgentScope scope = activateSpan(span);
      scope.setAsyncPropagation(true);

      // For use by servlet instrumentation
      exchange.putAttachment(DD_UNDERTOW_SPAN, span);
      exchange.putAttachment(DD_HTTPSERVEREXCHANGE_DISPATCH, false);

      // TODO is this required?
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
