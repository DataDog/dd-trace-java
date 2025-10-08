package datadog.trace.instrumentation.httpclient;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class JavaNetClientDecorator extends HttpClientDecorator<HttpRequest, HttpResponse<?>> {

  public static final CharSequence COMPONENT = UTF8BytesString.create("java-http-client");

  public static final JavaNetClientDecorator DECORATE = new JavaNetClientDecorator();

  public static final UTF8BytesString OPERATION_NAME =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"java-http-client"};
  }

  @Override
  protected CharSequence component() {
    return COMPONENT;
  }

  @Override
  protected String method(HttpRequest httpRequest) {
    return httpRequest.method();
  }

  @Override
  protected URI url(HttpRequest httpRequest) throws URISyntaxException {
    return httpRequest.uri();
  }

  @Override
  protected Object sourceUrl(final HttpRequest request) {
    return request.uri();
  }

  @Override
  protected int status(HttpResponse<?> httpResponse) {
    return httpResponse.statusCode();
  }

  @Override
  protected String getRequestHeader(HttpRequest request, String headerName) {
    return request.headers().firstValue(headerName).orElse(null);
  }

  @Override
  protected String getResponseHeader(HttpResponse<?> response, String headerName) {
    return response.headers().firstValue(headerName).orElse(null);
  }
}
