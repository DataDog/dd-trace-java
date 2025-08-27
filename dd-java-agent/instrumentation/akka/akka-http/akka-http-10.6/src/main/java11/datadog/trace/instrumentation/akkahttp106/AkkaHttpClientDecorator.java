package datadog.trace.instrumentation.akkahttp106;

import akka.http.javadsl.model.HttpHeader;
import akka.http.scaladsl.model.HttpRequest;
import akka.http.scaladsl.model.HttpResponse;
import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;

public class AkkaHttpClientDecorator extends HttpClientDecorator<HttpRequest, HttpResponse> {
  public static final CharSequence AKKA_HTTP_CLIENT = UTF8BytesString.create("akka-http-client");
  public static final AkkaHttpClientDecorator DECORATE = new AkkaHttpClientDecorator();
  public static final CharSequence AKKA_CLIENT_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"akka-http", "akka-http-client"};
  }

  @Override
  protected CharSequence component() {
    return AKKA_HTTP_CLIENT;
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

  @Override
  public AgentSpan onError(final AgentSpan span, final Throwable throwable) {
    if (throwable != null && throwable.getClass().getName().contains("StreamTcpException")) {
      Throwable cause = throwable.getCause();
      if (cause != null) {
        if (cause instanceof java.net.ConnectException) {
          return super.onError(span, new java.net.ConnectException(throwable.getMessage()));
        } else if (cause instanceof java.net.SocketTimeoutException) {
          return super.onError(span, new java.net.SocketTimeoutException(throwable.getMessage()));
        }
      }
    }
    return super.onError(span, throwable);
  }
}
