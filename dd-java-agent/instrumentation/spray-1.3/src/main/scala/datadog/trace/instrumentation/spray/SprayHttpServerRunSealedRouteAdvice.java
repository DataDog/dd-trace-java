package datadog.trace.instrumentation.spray;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.spray.SprayHttpServerDecorator.DECORATE;
import static datadog.trace.instrumentation.spray.SprayHttpServerDecorator.SPRAY_HTTP_REQUEST;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import spray.http.HttpRequest;
import spray.routing.RequestContext;

public class SprayHttpServerRunSealedRouteAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope enter(@Advice.Argument(value = 1, readOnly = false) RequestContext ctx) {
    final AgentSpan span;
    final AgentSpan.Context.Extracted extractedContext;
    if (activeSpan() == null) {
      // Propagate context in case income request was going through several routes
      // TODO: Add test for it
      final HttpRequest request = ctx.request();
      extractedContext = DECORATE.extract(request);
      span = DECORATE.startSpan(request, extractedContext);
    } else {
      extractedContext = null;
      span = startSpan(SPRAY_HTTP_REQUEST);
    }

    DECORATE.afterStart(span);

    final AgentScope scope = activateSpan(span);
    scope.setAsyncPropagation(true);
    ctx = SprayHelper.wrapRequestContext(ctx, scope.span(), extractedContext);
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
