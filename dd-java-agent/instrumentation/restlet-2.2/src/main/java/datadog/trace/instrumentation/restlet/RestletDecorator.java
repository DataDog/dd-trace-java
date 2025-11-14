package datadog.trace.instrumentation.restlet;

import static datadog.trace.instrumentation.restlet.RestletExtractAdapter.Request;
import static datadog.trace.instrumentation.restlet.RestletExtractAdapter.Response;

import com.sun.net.httpserver.HttpExchange;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;

public class RestletDecorator
    extends HttpServerDecorator<HttpExchange, HttpExchange, HttpExchange, HttpExchange> {
  public static final CharSequence RESTLET_HTTP_SERVER =
      UTF8BytesString.create("restlet-http-server");
  public static final RestletDecorator DECORATE = new RestletDecorator();

  private static final CharSequence RESTLET_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"restlet-http", "restlet-http-server"};
  }

  @Override
  protected CharSequence component() {
    return RESTLET_HTTP_SERVER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpExchange> getter() {
    return Request.GETTER;
  }

  @Override
  protected AgentPropagation.ContextVisitor<HttpExchange> responseGetter() {
    return Response.GETTER;
  }

  @Override
  public CharSequence spanName() {
    return RESTLET_REQUEST;
  }

  @Override
  protected String method(final HttpExchange exchange) {
    return exchange.getRequestMethod();
  }

  @Override
  protected URIDataAdapter url(final HttpExchange exchange) {
    return new HttpExchangeURIDataAdapter(exchange);
  }

  @Override
  protected String peerHostIP(final HttpExchange exchange) {
    return exchange.getRemoteAddress().getAddress().getHostAddress();
  }

  @Override
  protected int peerPort(final HttpExchange exchange) {
    return exchange.getRemoteAddress().getPort();
  }

  @Override
  protected int status(final HttpExchange exchange) {
    return exchange.getResponseCode();
  }

  @Override
  protected String getRequestHeader(final HttpExchange exchange, String key) {
    return exchange.getRequestHeaders().getFirst(key);
  }
}
