package datadog.trace.instrumentation.javahttpclient;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class JavaHttpClientDecorator extends HttpClientDecorator<HttpRequest, HttpResponse<?>> {
  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create("http.request");
  public static final CharSequence JAVA_HTTP_CLIENT =
      UTF8BytesString.create("java-httpclient");
  public static final JavaHttpClientDecorator DECORATE = new JavaHttpClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[]{"httpclient", "java-httpclient", "java-http-client"};
  }

  @Override
  protected CharSequence component() {
    return JAVA_HTTP_CLIENT;
  }

  @Override
  protected String method(final HttpRequest httpRequest) {
    return httpRequest.method();
  }

  @Override
  protected URI url(final HttpRequest request) {
    return request.uri();
  }

  @Override
  protected int status(final HttpResponse<?> httpResponse) {
    return httpResponse.statusCode();
  }
}
