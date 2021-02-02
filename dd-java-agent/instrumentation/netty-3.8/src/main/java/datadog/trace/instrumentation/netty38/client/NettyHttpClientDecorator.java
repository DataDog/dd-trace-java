package datadog.trace.instrumentation.netty38.client;

import static org.jboss.netty.handler.codec.http.HttpHeaders.Names.HOST;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;
import lombok.extern.slf4j.Slf4j;
import org.jboss.netty.handler.codec.http.HttpRequest;
import org.jboss.netty.handler.codec.http.HttpResponse;

@Slf4j
public class NettyHttpClientDecorator extends HttpClientDecorator<HttpRequest, HttpResponse> {
  public static final CharSequence NETTY_CLIENT = UTF8BytesString.createConstant("netty-client");
  public static final CharSequence NETTY_CLIENT_REQUEST =
      UTF8BytesString.createConstant("netty.client.request");

  public static final NettyHttpClientDecorator DECORATE = new NettyHttpClientDecorator("http://");
  public static final NettyHttpClientDecorator DECORATE_SECURE =
      new NettyHttpClientDecorator("https://");

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
    final URI uri = new URI(request.getUri());
    if ((uri.getHost() == null || uri.getHost().equals("")) && request.headers().contains(HOST)) {
      return new URI(uriPrefix + request.headers().get(HOST) + request.getUri());
    } else {
      return uri;
    }
  }

  @Override
  protected int status(final HttpResponse httpResponse) {
    return httpResponse.getStatus().getCode();
  }
}
