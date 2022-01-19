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
import static datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator.DD_SPAN_ATTRIBUTE;
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
    return new String[] {
      // add classes here
    };
  }

  // @Advice.Argument(value = 0, readOnly = false) HttpServerExchange exchange
  public static class HandlerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope onEnter(
        @Advice.Argument(value = 0, readOnly = false) HttpServerExchange exchange) {

      Object existingSpan = exchange.getRequestHeaders().get(DD_SPAN_ATTRIBUTE);
      if (existingSpan instanceof AgentSpan) {
        // Request already gone through initial processing, so just activate the span.
        ((AgentSpan) existingSpan).finishThreadMigration();
        return activateSpan((AgentSpan) existingSpan);
      }

      System.err.println("exchange: " + exchange);
      System.err.println("Undertow handler");
      final AgentSpan.Context.Extracted extractedContext = DECORATE.extract(exchange);
      final AgentSpan span = DECORATE.startSpan(exchange, extractedContext).setMeasured(true);

      // The openTelemetry implementation does some confusing stuff that I don't know how to
      // replicate.

      return null;
    }

    //    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    //    public static void onExit(
    //        @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    //      DECORATE.onError(scope.span(), throwable);
    //      DECORATE.beforeFinish(scope.span());
    //      scope.close();
    //      scope.span().finish();
    //    }
  }
}
