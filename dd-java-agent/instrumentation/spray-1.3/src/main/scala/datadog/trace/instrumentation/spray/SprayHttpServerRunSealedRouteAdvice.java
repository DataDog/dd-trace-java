package datadog.trace.instrumentation.spray;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.spray.SprayHttpServerDecorator.DECORATE;

import datadog.context.Context;
import datadog.context.ContextScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import net.bytebuddy.asm.Advice;
import spray.http.HttpRequest;
import spray.routing.RequestContext;

public class SprayHttpServerRunSealedRouteAdvice {
  @Advice.OnMethodEnter(suppress = Throwable.class)
  public static ContextScope enter(
      @Advice.Argument(value = 1, readOnly = false) RequestContext ctx) {
    final AgentSpan span;
    final Context extractedContext;
    final ContextScope scope;
    if (activeSpan() == null) {
      // Propagate context in case income request was going through several routes
      // TODO: Add test for it
      final HttpRequest request = ctx.request();
      extractedContext = DECORATE.extractContext(request);
      span = DECORATE.startSpan(request, extractedContext);
      scope = extractedContext.with(span).attach();
    } else {
      extractedContext = null;
      span = startSpan(DECORATE.spanName());
      scope = span.attach();
    }

    DECORATE.afterStart(span);

    ctx =
        SprayHelper.wrapRequestContext(
            ctx, span, DECORATE.getExtractedSpanContext(extractedContext));
    return scope;
  }

  @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
  public static void exit(
      @Advice.Enter final ContextScope scope, @Advice.Thrown final Throwable throwable) {
    if (throwable != null) {
      DECORATE.onError(scope, throwable);
    }
    scope.close();
  }
}
