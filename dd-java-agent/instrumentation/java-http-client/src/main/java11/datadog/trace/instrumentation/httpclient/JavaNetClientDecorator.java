package datadog.trace.instrumentation.httpclient;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

public class JavaNetClientDecorator extends HttpClientDecorator<HttpRequest, HttpResponse<?>> {

  public static final CharSequence COMPONENT = UTF8BytesString.create("java-http-client");

  public static final String OPERATION_NAME = "http.request";

  public static final JavaNetClientDecorator DECORATE = new JavaNetClientDecorator();

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
  protected int status(HttpResponse<?> httpResponse) {
    return httpResponse.statusCode();
  }
}
