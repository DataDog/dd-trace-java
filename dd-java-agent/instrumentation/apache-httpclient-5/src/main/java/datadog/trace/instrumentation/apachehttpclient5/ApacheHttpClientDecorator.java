package datadog.trace.instrumentation.apachehttpclient5;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.HttpRequest;
import org.apache.hc.core5.http.HttpResponse;

public class ApacheHttpClientDecorator extends HttpClientDecorator<HttpRequest, HttpResponse> {

  public static final CharSequence APACHE_HTTP_CLIENT =
      UTF8BytesString.create("apache-httpclient5");
  public static final ApacheHttpClientDecorator DECORATE = new ApacheHttpClientDecorator();
  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {
      "httpclient5",
      "apache-httpclient5",
      "apache-http-client5",
      "httpclient",
      "apache-httpclient",
      "apache-http-client"
    };
  }

  @Override
  protected CharSequence component() {
    return APACHE_HTTP_CLIENT;
  }

  @Override
  protected String method(final HttpRequest httpRequest) {
    return httpRequest.getMethod();
  }

  @Override
  protected URI url(final HttpRequest request) throws URISyntaxException {
    return request.getUri();
  }

  @Override
  protected HttpRequest sourceUrl(final HttpRequest request) {
    return request;
  }

  @Override
  protected int status(final HttpResponse httpResponse) {
    return httpResponse.getCode();
  }

  @Override
  protected String getRequestHeader(HttpRequest request, String headerName) {
    Header header = request.getFirstHeader(headerName);
    if (null != header) {
      return header.getValue();
    }
    return null;
  }

  @Override
  protected String getResponseHeader(HttpResponse response, String headerName) {
    Header header = response.getFirstHeader(headerName);
    if (null != header) {
      return header.getValue();
    }
    return null;
  }
}
