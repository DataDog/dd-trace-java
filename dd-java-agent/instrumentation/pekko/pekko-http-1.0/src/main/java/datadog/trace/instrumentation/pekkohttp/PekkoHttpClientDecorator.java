package datadog.trace.instrumentation.pekkohttp;

import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.pekko.http.javadsl.model.HttpHeader;
import org.apache.pekko.http.scaladsl.model.HttpRequest;
import org.apache.pekko.http.scaladsl.model.HttpResponse;

public class PekkoHttpClientDecorator extends HttpClientDecorator<HttpRequest, HttpResponse> {
  public static final CharSequence PEKKO_HTTP_CLIENT = UTF8BytesString.create("pekko-http-client");
  public static final PekkoHttpClientDecorator DECORATE = new PekkoHttpClientDecorator();
  public static final CharSequence PEKKO_CLIENT_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"pekko-http", "pekko-http-client"};
  }

  @Override
  protected CharSequence component() {
    return PEKKO_HTTP_CLIENT;
  }

  @Override
  protected String method(final HttpRequest httpRequest) {
    return httpRequest.method().value();
  }

  @Override
  protected URI url(final HttpRequest httpRequest) throws URISyntaxException {
    return URIUtils.safeParse(httpRequest.uri().toString());
  }

  @Override
  protected int status(final HttpResponse httpResponse) {
    return httpResponse.status().intValue();
  }

  @Override
  protected String getRequestHeader(HttpRequest request, String headerName) {
    return request.getHeader(headerName).map(HttpHeader::value).orElse(null);
  }

  @Override
  protected String getResponseHeader(HttpResponse response, String headerName) {
    return response.getHeader(headerName).map(HttpHeader::value).orElse(null);
  }
}
