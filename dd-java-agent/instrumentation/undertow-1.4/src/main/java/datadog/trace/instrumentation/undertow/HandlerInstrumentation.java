package datadog.trace.instrumentation.undertow;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import io.undertow.server.HttpServerExchange;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;

import static datadog.trace.agent.tooling.bytebuddy.matcher.DDElementMatchers.implementsInterface;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DD_SPAN_ATTRIBUTE;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

@AutoService(Instrumenter.class)
public final class HandlerInstrumentation extends Instrumenter.Tracing {

  public HandlerInstrumentation() {
    super("undertow", "wildfly");
  }

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return implementsInterface(named("io.undertow.server.HttpHandler"));
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
        packageName + ".UndertowExtractAdapter"
    };
  }

  // @Advice.Argument(value = 0, readOnly = false) HttpServerExchange exchange
  public static class HandlerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(value = 0, readOnly = false) HttpServerExchange exchange) {

      AgentSpan existingSpan = exchange.getAttachment(DD_SPAN_ATTRIBUTE);

      if (existingSpan != null) {
        // Request already gone through initial processing, so just activate the span.
        existingSpan.finishThreadMigration();
        return activateSpan(existingSpan);
      }

      final AgentSpan.Context.Extracted extractedContext = DECORATE.extract(exchange);
      final AgentSpan span = DECORATE.startSpan(exchange, extractedContext).setMeasured(true);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, exchange, exchange, extractedContext);

      final AgentScope scope = activateSpan(span);
      scope.setAsyncPropagation(true);

      exchange.putAttachment(DD_SPAN_ATTRIBUTE, span);

      exchange.addExchangeCompleteListener(new ExchangeEndSpanListener(span));

      // request may be processed on any thread; signal thread migration
      span.startThreadMigration();
      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeScope(@Advice.Enter final AgentScope scope) {
      scope.span().finishWork();
      scope.close();
    }
  }
}
