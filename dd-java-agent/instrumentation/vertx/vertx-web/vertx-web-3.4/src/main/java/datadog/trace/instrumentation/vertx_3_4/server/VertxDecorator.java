package datadog.trace.instrumentation.vertx_3_4.server;

import datadog.context.Context;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapterBase;
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
    return routingContext.request().rawMethod();
  }

  @Override
  protected URIDataAdapter url(final RoutingContext routingContext) {
    return new VertxURIDataAdapter(routingContext);
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

  protected static final class VertxURIDataAdapter extends URIDataAdapterBase {
    private final RoutingContext routingContext;

    public VertxURIDataAdapter(final RoutingContext routingContext) {
      this.routingContext = routingContext;
    }

    @Override
    public String scheme() {
      return routingContext.request().scheme();
    }

    @Override
    public String host() {
      return routingContext.request().host();
    }

    @Override
    public int port() {
      return routingContext.request().localAddress().port();
    }

    @Override
    public String path() {
      return routingContext.request().path();
    }

    @Override
    public String fragment() {
      return null;
    }

    @Override
    public String query() {
      return routingContext.request().query();
    }

    @Override
    public boolean supportsRaw() {
      return false;
    }

    @Override
    public String rawPath() {
      return null;
    }

    @Override
    public String rawQuery() {
      return null;
    }
  }
}
