package datadog.trace.instrumentation.spray;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.spray.SprayHttpServerDecorator.DECORATE;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.AgentSpanContext;
import net.bytebuddy.asm.Advice;
import spray.http.HttpRequest;
import spray.routing.RequestContext;

public class SprayHttpServerRunSealedRouteAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static AgentScope enter(@Advice.Argument(value = 1, readOnly = false) RequestContext ctx) {
    final AgentSpan span;
    Context extractedContext = null;
    AgentSpanContext.Extracted extractedSpanContext = null;
    if (activeSpan() == null) {
      // Propagate context in case income request was going through several routes
      // TODO: Add test for it
      final HttpRequest request = ctx.request();
      extractedContext = DECORATE.extract(request);
      final AgentSpan extractedSpan = AgentSpan.fromContext(extractedContext);
      extractedSpanContext =
          extractedSpan == null ? null : (AgentSpanContext.Extracted) extractedSpan.context();
      span = DECORATE.startSpan(request, extractedSpanContext);
    } else {
      extractedContext = null;
      span = startSpan(DECORATE.spanName());
    }
    final AgentScope scope =
        extractedContext == null
            ? activateSpan(span)
            : (AgentScope) extractedContext.with(span).attach();

    DECORATE.afterStart(span);

    ctx = SprayHelper.wrapRequestContext(ctx, span, extractedSpanContext);
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
