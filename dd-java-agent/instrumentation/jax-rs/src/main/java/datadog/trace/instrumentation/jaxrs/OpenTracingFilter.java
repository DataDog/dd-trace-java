package datadog.trace.instrumentation.jaxrs;

import io.opentracing.Scope;
import io.opentracing.Span;
import io.opentracing.Tracer;
import io.opentracing.util.GlobalTracer;

import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.container.ContainerRequestFilter;
import javax.ws.rs.container.ContainerResponseContext;
import javax.ws.rs.container.ContainerResponseFilter;
import javax.ws.rs.ext.Provider;

@Provider
public final class OpenTracingFilter implements ContainerRequestFilter, ContainerResponseFilter {
  private static final String SPAN_KEY = "ot-request-span";

  @Override
  public void filter(ContainerRequestContext containerRequestContext) {
    final Tracer tracer = GlobalTracer.get();
    final Scope scope = tracer.scopeManager().active();
    if (scope == null) {
      final Span span = tracer.buildSpan("request-parent").start();
      tracer.scopeManager().activate(span, true);
      containerRequestContext.setProperty(SPAN_KEY, span);
    }
  }

  @Override
  public void filter(ContainerRequestContext containerRequestContext, ContainerResponseContext containerResponseContext) {
    final Object property = containerRequestContext.getProperty(SPAN_KEY);
    if (property != null && property instanceof Span) {
      ((Span) property).finish();
    }
  }
}
