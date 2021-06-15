package datadog.trace.instrumentation.spray;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.propagate;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.spray.SprayHeaders.GETTER;
import static datadog.trace.instrumentation.spray.SprayHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.spray.SprayHttpServerDecorator.SPRAY_HTTP_REQUEST;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import spray.routing.RequestContext;

public class SprayHttpServerRunSealedRouteAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope enter(@Advice.Argument(value = 1, readOnly = false) RequestContext ctx) {
    final AgentSpan span;
    if (activeSpan() == null) {
      // Propagate context in case income request was going through several routes
      // TODO: Add test for it
      final AgentSpan.Context extractedContext = propagate().extract(ctx.request(), GETTER);
      span = startSpan(SPRAY_HTTP_REQUEST, extractedContext);
    } else {
      span = startSpan(SPRAY_HTTP_REQUEST);
    }

    DECORATE.afterStart(span);

    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);
    ctx = SprayHelper.wrapRequestContext(ctx, scope.span());
    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void exit(
      @Advice.Enter final AgentScope scope, @Advice.Thrown final Throwable throwable) {
    if (throwable != null) {
      DECORATE.onError(scope, throwable);
    }
    scope.close();
  }
}
