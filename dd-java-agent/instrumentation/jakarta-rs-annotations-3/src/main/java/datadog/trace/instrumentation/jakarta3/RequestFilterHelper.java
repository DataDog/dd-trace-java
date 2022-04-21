package datadog.trace.instrumentation.jakarta3;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jakarta3.JakartaRsAnnotationsDecorator.DECORATE;
import static datadog.trace.instrumentation.jakarta3.JakartaRsAnnotationsDecorator.JAKARTA_RS_REQUEST_ABORT;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.ws.rs.container.ContainerRequestContext;
import java.lang.reflect.Method;

public class RequestFilterHelper {
  public static AgentScope createOrUpdateAbortSpan(
      final ContainerRequestContext context, final Class resourceClass, final Method method) {

    if (method != null && resourceClass != null) {
      context.setProperty(JakartaRsAnnotationsDecorator.ABORT_HANDLED, true);
      // The ordering of the specific and general abort instrumentation is unspecified
      // The general instrumentation (ContainerRequestFilterInstrumentation) saves spans
      // properties if it ran first
      AgentSpan parent =
          (AgentSpan) context.getProperty(JakartaRsAnnotationsDecorator.ABORT_PARENT);
      AgentSpan span = (AgentSpan) context.getProperty(JakartaRsAnnotationsDecorator.ABORT_SPAN);

      if (span == null) {
        parent = activeSpan();
        span = startSpan(JAKARTA_RS_REQUEST_ABORT);

        final AgentScope scope = activateSpan(span);
        scope.setAsyncPropagation(true);

        DECORATE.afterStart(span);
        DECORATE.onJakartaRsSpan(span, parent, resourceClass, method, null);

        return scope;
      } else {
        DECORATE.onJakartaRsSpan(span, parent, resourceClass, method, null);
        return null;
      }
    } else {
      return null;
    }
  }

  public static void closeSpanAndScope(final AgentScope scope, final Throwable throwable) {
    if (scope == null) {
      return;
    }

    final AgentSpan span = scope.span();
    if (throwable != null) {
      DECORATE.onError(span, throwable);
    }

    DECORATE.beforeFinish(span);
    scope.close();
    span.finish();
  }
}
