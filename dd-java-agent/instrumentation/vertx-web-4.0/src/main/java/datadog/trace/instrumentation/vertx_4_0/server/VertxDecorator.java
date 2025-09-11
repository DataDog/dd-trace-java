package datadog.trace.instrumentation.vertx_4_0.server;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase;
import datadog.trace.bootstrap.instrumentation.api.URIDefaultDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;

public class VertxDecorator
    extends HttpServerDecorator<RoutingContext, RoutingContext, HttpServerResponse, Void> {
  static final CharSequence INSTRUMENTATION_NAME = UTF8BytesString.create("vertx.route-handler");

  private static final CharSequence COMPONENT_NAME = UTF8BytesString.create("vertx");

  static final VertxDecorator DECORATE = new VertxDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {INSTRUMENTATION_NAME.toString()};
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected AgentPropagation.ContextVisitor<Void> getter() {
    return null;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpServerResponse> responseGetter() {
    return null;
  }

  @Override
  public CharSequence spanName() {
    return INSTRUMENTATION_NAME;
  }

  @Override
  protected String method(final RoutingContext routingContext) {
    return routingContext.request().method().name();
  }

  @Override
  protected URIDataAdapter url(final RoutingContext routingContext) {
    return URIDataAdapterBase.fromURI(routingContext.request().uri(), URIDefaultDataAdapter::new);
  }

  @Override
  public AgentSpan onRequest(
      final AgentSpan span,
      final RoutingContext connection,
      final RoutingContext routingContext,
      final Context parentContext) {
    return span;
  }

  @Override
  protected String peerHostIP(final RoutingContext routingContext) {
    return routingContext.request().connection().remoteAddress().host();
  }

  @Override
  protected int peerPort(final RoutingContext routingContext) {
    return routingContext.request().connection().remoteAddress().port();
  }

  @Override
  protected int status(final HttpServerResponse httpServerResponse) {
    return httpServerResponse.getStatusCode();
  }
}
