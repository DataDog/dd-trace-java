package datadog.trace.instrumentation.micronaut.v4_0;

import static datadog.trace.bootstrap.instrumentation.decorator.http.HttpResourceDecorator.HTTP_RESOURCE_DECORATOR;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.ErrorPriorities;
import datadog.trace.bootstrap.instrumentation.api.InternalSpanTypes;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.web.router.UriRouteMatch;
import java.util.concurrent.CompletionException;

public class MicronautDecorator
    extends HttpServerDecorator<HttpRequest, HttpRequest, HttpResponse, Void> {
  private static final CharSequence MICRONAUT_CONTROLLER =
      UTF8BytesString.create("micronaut-controller");
  public static final String SPAN_ATTRIBUTE = "datadog.trace.instrumentation.micronaut-netty.Span";
  public static final String PARENT_SPAN_ATTRIBUTE =
      "datadog.trace.instrumentation.micronaut-netty.ParentSpan";

  public static MicronautDecorator DECORATE = new MicronautDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"micronaut"};
  }

  @Override
  protected AgentPropagation.ContextVisitor<Void> getter() {
    return null;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpResponse> responseGetter() {
    return null;
  }

  @Override
  public CharSequence spanName() {
    return MICRONAUT_CONTROLLER;
  }

  @Override
  protected String method(HttpRequest httpRequest) {
    return httpRequest.getMethod().name();
  }

  @Override
  protected URIDataAdapter url(HttpRequest httpRequest) {
    return null;
  }

  @Override
  protected String peerHostIP(HttpRequest httpRequest) {
    return null;
  }

  @Override
  protected int peerPort(HttpRequest httpRequest) {
    return 0;
  }

  @Override
  protected int status(HttpResponse mutableHttpResponse) {
    return mutableHttpResponse.getStatus().getCode();
  }

  @Override
  protected CharSequence spanType() {
    return InternalSpanTypes.HTTP_SERVER;
  }

  @Override
  protected CharSequence component() {
    return MICRONAUT_CONTROLLER;
  }

  public void onMicronautSpan(
      final AgentSpan span,
      final AgentSpan parent,
      final HttpRequest<?> request,
      final UriRouteMatch uriRouteMatch) {
    String route = null;
    if (uriRouteMatch.getRouteInfo() != null
        && uriRouteMatch.getRouteInfo().getUriMatchTemplate() != null) {
      route = uriRouteMatch.getRouteInfo().getUriMatchTemplate().toPathString();
    }
    if (null != route) {
      HTTP_RESOURCE_DECORATOR.withRoute(parent, request.getMethod().name(), route);
    }
    span.setResourceName(DECORATE.spanNameForMethod(uriRouteMatch.getTargetMethod()));
  }

  /**
   * Records an exception that Micronaut is about to route to an error handler ({@code @Error} route
   * or {@code ExceptionHandler} bean) or to the default error response. Uses the HTTP server
   * decorator priority so the response status still decides whether the span is flagged as an
   * error: a handled exception mapped to a 4xx keeps its error tags without marking the span.
   */
  public void onRoutedError(final HttpRequest<?> request, Throwable cause) {
    if (request == null || cause == null) {
      return;
    }
    AgentSpan span = request.getAttribute(SPAN_ATTRIBUTE, AgentSpan.class).orElse(null);
    if (span == null) {
      return;
    }
    if (cause instanceof CompletionException && cause.getCause() != null) {
      cause = cause.getCause();
    }
    onError(span, cause, ErrorPriorities.HTTP_SERVER_DECORATOR);
  }
}
