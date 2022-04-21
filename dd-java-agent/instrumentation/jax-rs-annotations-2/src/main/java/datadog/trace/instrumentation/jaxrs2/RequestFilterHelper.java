package datadog.trace.instrumentation.jaxrs2;

import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activeSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.jaxrs2.JaxRsAnnotationsDecorator.DECORATE;
import static datadog.trace.instrumentation.jaxrs2.JaxRsAnnotationsDecorator.JAX_RS_REQUEST_ABORT;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import java.lang.reflect.Method;
import javax.ws.rs.container.ContainerRequestContext;

public class RequestFilterHelper {
  public static AgentScope createOrUpdateAbortSpan(
      final ContainerRequestContext context, final Class resourceClass, final Method method) {

    if (method != null && resourceClass != null) {
      context.setProperty(JaxRsAnnotationsDecorator.ABORT_HANDLED, true);
      // The ordering of the specific and general abort instrumentation is unspecified
      // The general instrumentation (ContainerRequestFilterInstrumentation) saves spans
      // properties if it ran first
      AgentSpan parent = (AgentSpan) context.getProperty(JaxRsAnnotationsDecorator.ABORT_PARENT);
      AgentSpan span = (AgentSpan) context.getProperty(JaxRsAnnotationsDecorator.ABORT_SPAN);

      if (span == null) {
        parent = activeSpan();
        span = startSpan(JAX_RS_REQUEST_ABORT);

        final AgentScope scope = activateSpan(span);
        scope.setAsyncPropagation(true);

        DECORATE.afterStart(span);
        DECORATE.onJaxRsSpan(span, parent, resourceClass, method, null);

        return scope;
      } else {
        DECORATE.onJaxRsSpan(span, parent, resourceClass, method, null);
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
