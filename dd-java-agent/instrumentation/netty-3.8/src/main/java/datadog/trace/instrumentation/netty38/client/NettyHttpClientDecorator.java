package datadog.trace.instrumentation.netty38.client;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.HOST;

import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

public class NettyHttpClientDecorator extends HttpClientDecorator<HttpRequest, HttpResponse> {
  public static final CharSequence NETTY_CLIENT = UTF8BytesString.create("netty-client");

  public static final NettyHttpClientDecorator DECORATE = new NettyHttpClientDecorator("http://");
  public static final NettyHttpClientDecorator DECORATE_SECURE =
      new NettyHttpClientDecorator("https://");

  public static final CharSequence NETTY_CLIENT_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  private final String uriPrefix;

  public NettyHttpClientDecorator(String uriPrefix) {
    this.uriPrefix = uriPrefix;
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"netty", "netty-3.9"};
  }

  @Override
  protected CharSequence component() {
    return NETTY_CLIENT;
  }

  @Override
  protected String method(final HttpRequest httpRequest) {
    return httpRequest.getMethod().getName();
  }

  @Override
  protected URI url(final HttpRequest request) throws URISyntaxException {
    final URI uri = URIUtils.safeParse(request.getUri());
    if ((uri.getHost() == null || uri.getHost().equals("")) && request.headers().contains(HOST)) {
      return URIUtils.safeParse(uriPrefix + request.headers().get(HOST) + request.getUri());
    }
    return uri;
  }

  @Override
  protected int status(final HttpResponse httpResponse) {
    return httpResponse.getStatus().getCode();
  }

  @Override
  protected String getRequestHeader(HttpRequest request, String headerName) {
    return request.headers().get(headerName);
  }

  @Override
  protected String getResponseHeader(HttpResponse response, String headerName) {
    return response.headers().get(headerName);
  }
}
