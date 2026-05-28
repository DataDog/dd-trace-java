package datadog.trace.instrumentation.quarkus_rest_client_reactive;

import static datadog.context.Context.current;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.activateSpan;
import static datadog.trace.bootstrap.instrumentation.api.AgentTracer.startSpan;
import static datadog.trace.instrumentation.quarkus_rest_client_reactive.InjectAdapter.SETTER;
import static datadog.trace.instrumentation.quarkus_rest_client_reactive.QuarkusRestClientDecorator.DECORATE;
import static datadog.trace.instrumentation.quarkus_rest_client_reactive.QuarkusRestClientDecorator.QUARKUS_REST_CLIENT;
import static datadog.trace.instrumentation.quarkus_rest_client_reactive.QuarkusRestClientDecorator.QUARKUS_REST_CLIENT_CALL;

import datadog.trace.bootstrap.instrumentation.api.AgentScope;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.client.ClientResponseContext;
import jakarta.ws.rs.client.ClientResponseFilter;

@Priority(Priorities.HEADER_DECORATOR)
public class QuarkusRestClientTracingFilter implements ClientRequestFilter, ClientResponseFilter {

  public static final String SPAN_PROPERTY_NAME = "datadog.trace.quarkus-rest-client.span";

  @Override
  public void filter(final ClientRequestContext requestContext) {
    final AgentSpan span = startSpan(QUARKUS_REST_CLIENT.toString(), QUARKUS_REST_CLIENT_CALL);
    try (final AgentScope scope = activateSpan(span)) {
      DECORATE.afterStart(span);
      DECORATE.onRequest(span, requestContext);
      DECORATE.injectContext(current().with(span), requestContext.getHeaders(), SETTER);
      requestContext.setProperty(SPAN_PROPERTY_NAME, span);
    }
  }

  @Override
  public void filter(
      final ClientRequestContext requestContext, final ClientResponseContext responseContext) {
    final Object spanObj = requestContext.getProperty(SPAN_PROPERTY_NAME);
    if (spanObj instanceof AgentSpan) {
      final AgentSpan span = (AgentSpan) spanObj;
      DECORATE.onResponse(span, responseContext);
      DECORATE.beforeFinish(span);
      span.finish();
    }
  }
}
