package datadog.trace.instrumentation.vertx_3_4.server;

import datadog.trace.api.Function;
import datadog.trace.api.cache.DDCache;
import datadog.trace.api.cache.DDCaches;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.Pair;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import io.vertx.core.http.HttpServerResponse;
import io.vertx.ext.web.RoutingContext;

public class VertxRouterDecorator
    extends HttpServerDecorator<RoutingContext, RoutingContext, HttpServerResponse> {

  static final CharSequence INSTRUMENTATION_NAME = UTF8BytesString.createConstant("vertx.router");

  private static final CharSequence COMPONENT_NAME = UTF8BytesString.createConstant("vertx");

  private static final Function<Pair<String, Object>, CharSequence> RESOURCE_NAME_JOINER =
      new Function<Pair<String, Object>, CharSequence>() {
        @Override
        public CharSequence apply(final Pair<String, Object> input) {
          return UTF8BytesString.create(input.getLeft() + " " + input.getRight());
        }
      };
  private static final DDCache<Pair<String, Object>, CharSequence> RESOURCE_NAME_CACHE =
      DDCaches.newFixedSizeCache(64);

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
    return new VertxURIDataAdapter(routingContext);
  }

  @Override
  public AgentSpan onRequest(final AgentSpan span, final RoutingContext routingContext) {
    if (routingContext != null) {
      final String method = routingContext.request().rawMethod();
      final String bestMatchingPattern = routingContext.currentRoute().getPath();

      if (method != null && bestMatchingPattern != null) {
        final CharSequence resourceName =
            RESOURCE_NAME_CACHE.computeIfAbsent(
                Pair.of(method, bestMatchingPattern), RESOURCE_NAME_JOINER);
        span.setResourceName(resourceName);
      }
    }
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

  protected static class VertxURIDataAdapter implements URIDataAdapter {
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
      return "";
    }

    @Override
    public String query() {
      return routingContext.request().query();
    }
  }
}
