package datadog.trace.instrumentation.restlet;

import static datadog.trace.agent.tooling.InstrumenterModule.TargetSystem.CONTEXT_TRACKING;
import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.getCurrentContext;
import static datadog.trace.bootstrap.instrumentation.api.Java8BytecodeBridge.spanFromContext;
import static datadog.trace.instrumentation.restlet.RestletDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.sun.net.httpserver.HttpExchange;
import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.agent.tooling.InstrumenterModule;
import datadog.trace.agent.tooling.annotation.AppliesOn;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(InstrumenterModule.class)
public final class RestletInstrumentation extends InstrumenterModule.Tracing
    implements Instrumenter.ForKnownTypes, Instrumenter.HasMethodAdvice {

  public RestletInstrumentation() {
    super("restlet-http", "restlet-http-server");
  }

  @Override
  public String[] knownMatchingTypes() {
    return new String[] {
      "org.restlet.engine.connector.HttpServerHelper$1",
      "org.restlet.engine.connector.HttpsServerHelper$2"
    };
  }

  @Override
  public void methodAdvice(MethodTransformer transformer) {
    transformer.applyAdvices(
        isMethod()
            .and(named("handle"))
            .and(takesArgument(0, named("com.sun.net.httpserver.HttpExchange"))),
        getClass().getName() + "$ContextTrackingAdvice",
        getClass().getName() + "$RestletHandleAdvice");
  }

  @Override
  public String[] helperClassNames() {
    return new String[] {
      packageName + ".RestletExtractAdapter",
      packageName + ".RestletExtractAdapter$Request",
      packageName + ".RestletExtractAdapter$Response",
      packageName + ".RestletDecorator",
      packageName + ".HttpExchangeURIDataAdapter"
    };
  }

  @AppliesOn(CONTEXT_TRACKING)
  public static class ContextTrackingAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope onEnter(@Advice.Argument(0) final HttpExchange exchange) {
      Context parentContext = DECORATE.extract(exchange);
      return parentContext.attach();
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void closeScope(@Advice.Enter final ContextScope scope) {
      scope.close();
    }
  }

  public static class RestletHandleAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static ContextScope beginRequest(@Advice.Argument(0) final HttpExchange exchange) {
      Context parentContext =
          getCurrentContext(); // parent context attached by ContextTrackingAdvice
      Context context = DECORATE.startSpan(exchange, parentContext);
      AgentSpan span = spanFromContext(context);
      ContextScope scope = context.attach();
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, exchange, exchange, parentContext);
      DECORATE.onPeerConnection(span, exchange.getRemoteAddress());

      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void finishRequest(
        @Advice.Enter final ContextScope scope,
        @Advice.Argument(0) final HttpExchange exchange,
        @Advice.Thrown final Throwable error) {
      if (null == scope) {
        return;
      }

      AgentSpan span = spanFromContext(scope.context());
      DECORATE.onResponse(span, exchange);

      if (null != error) {
        DECORATE.onError(span, error);
      }

      DECORATE.beforeFinish(scope.context());
      scope.close();
      span.finish();
    }
  }
}
