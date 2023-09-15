package datadog.trace.instrumentation.undertow;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.undertow.UndertowBlockingHandler.REQUEST_BLOCKING_DATA;
import static datadog.trace.instrumentation.undertow.UndertowBlockingHandler.TRACE_SEGMENT;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DD_UNDERTOW_CONTINUATION;
import static datadog.trace.instrumentation.undertow.UndertowDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import com.google.auto.service.AutoService;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.api.gateway.Flow.Action.RequestBlockingAction;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentTracer;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class HandlerInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForSingleType {

  public HandlerInstrumentation() {
    super("undertow", "undertow-2.0");
  }

  @Override
  public String instrumentedType() {
    return "io.undertow.server.Connectors";
  }

  @Override
  public void adviceTransformations(AdviceTransformation transformation) {
    transformation.applyAdvice(
        isMethod()
            .and(named("executeRootHandler"))
            .and(takesArguments(2))
            .and(takesArgument(0, named("io.undertow.server.HttpHandler")))
            .and(takesArgument(1, named("io.undertow.server.HttpServerExchange")))
            .and(isStatic())
            .and(isPublic()),
        getClass().getName() + "$ExecuteRootHandlerAdvice");
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
      packageName + ".IgnoreSendAttribute",
      packageName + ".UndertowBlockResponseFunction",
    };
  }

  public static class ExecuteRootHandlerAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(
        @Advice.Argument(value = 0, readOnly = false) HttpHandler handler,
        @Advice.Argument(1) final HttpServerExchange exchange,
        @Advice.Local("agentScope") AgentScope scope) {
      AgentScope currentScope = AgentTracer.activeScope();
      if (currentScope != null) {
        AgentSpan localRootSpan = currentScope.span().getLocalRootSpan();
        if (DECORATE.spanName().equals(localRootSpan.getSpanName())) {
          // if we can here through the dispatch of an HttpHandler, rather than that of a
          // plain Runnable, then Connects.executeRootHandler() will still have been called,
          // and this advice executed.
          // However, a scope may have already been continued through UndertowRunnableWrapper.
          // This scope may refer to a child span of the original undertow root span, whereas
          // here we would only be able to activate the scope for the original undertow span.
          return;
        }
      }

      AgentScope.Continuation continuation = exchange.getAttachment(DD_UNDERTOW_CONTINUATION);
      if (continuation != null) {
        scope = continuation.activate();
        exchange.putAttachment(DD_UNDERTOW_CONTINUATION, scope.capture());
        return;
      }

      final AgentSpan.Context.Extracted extractedContext = DECORATE.extract(exchange);
      final AgentSpan span = DECORATE.startSpan(exchange, extractedContext).setMeasured(true);
      scope = activateSpan(span);
      scope.setAsyncPropagation(true);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, exchange, exchange, extractedContext);

      exchange.putAttachment(DD_UNDERTOW_CONTINUATION, scope.capture());

      exchange.addExchangeCompleteListener(ExchangeEndSpanListener.INSTANCE);

      // TODO is this required?
      // exchange.getRequestHeaders().add(
      //   new HttpString(CorrelationIdentifier.getTraceIdKey()), GlobalTracer.get().getTraceId());
      // exchange.getRequestHeaders().add(
      //   new HttpString(CorrelationIdentifier.getSpanIdKey()), GlobalTracer.get().getSpanId());

      RequestBlockingAction rab = span.getRequestBlockingAction();
      if (rab != null) {
        exchange.putAttachment(REQUEST_BLOCKING_DATA, rab);
        exchange.putAttachment(TRACE_SEGMENT, span.getRequestContext().getTraceSegment());
        handler = UndertowBlockingHandler.INSTANCE;
      }
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void closeScope(@Advice.Local("agentScope") final AgentScope scope) {
      if (scope == null) {
        return;
      }
      scope.close();
    }
  }
}
