package datadog.trace.instrumentation.restlet;

import static datadog.trace.agent.tooling.bytebuddy.matcher.NameMatchers.named;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.instrumentation.restlet.RestletDecorator.DECORATE;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import com.google.auto.service.AutoService;
import com.sun.net.httpserver.HttpExchange;
import datadog.trace.agent.tooling.Instrumenter;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;

@AutoService(Instrumenter.class)
public final class RestletInstrumentation extends Instrumenter.Tracing
    implements Instrumenter.ForKnownTypes {

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
    transformer.applyAdvice(
        isMethod()
            .and(named("handle"))
            .and(takesArgument(0, named("com.sun.net.httpserver.HttpExchange"))),
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

  public static class RestletHandleAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static AgentScope beginRequest(@Advice.Argument(0) final HttpExchange exchange) {
      AgentSpan.Context.Extracted context = DECORATE.extract(exchange);
      AgentSpan span = DECORATE.startSpan(exchange, context);
      AgentScope scope = activateSpan(span);
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, exchange, exchange, context);
      DECORATE.onPeerConnection(span, exchange.getRemoteAddress());

      return scope;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void finishRequest(
        @Advice.Enter final AgentScope scope,
        @Advice.Argument(0) final HttpExchange exchange,
        @Advice.Thrown final Throwable error) {
      if (null == scope) {
        return;
      }

      AgentSpan span = scope.span();
      DECORATE.onResponse(span, exchange);

      if (null != error) {
        DECORATE.onError(span, error);
      }

      DECORATE.beforeFinish(span);
      scope.close();
      span.finish();
    }
  }
}
