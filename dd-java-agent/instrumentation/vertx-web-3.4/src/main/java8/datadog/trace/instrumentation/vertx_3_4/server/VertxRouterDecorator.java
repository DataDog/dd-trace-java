package datadog.trace.instrumentation.vertx_3_4.server;

import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;

public class VertxRouterDecorator
    extends HttpServerDecorator<RoutingContext, RoutingContext, HttpServerResponse> {

  static final CharSequence INSTRUMENTATION_NAME = UTF8BytesString.createConstant("vertx.router");

  private static final CharSequence COMPONENT_NAME = UTF8BytesString.createConstant("vertx");

  static final VertxRouterDecorator DECORATE = new VertxRouterDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {INSTRUMENTATION_NAME.toString()};
  }

  @Override
  protected CharSequence component() {
    return COMPONENT_NAME;
  }

  @Override
  protected String method(final RoutingContext routingContext) {
    return routingContext.request().rawMethod();
  }

  @Override
  protected URIDataAdapter url(final RoutingContext routingContext) {
    return null;
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
