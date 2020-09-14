package datadog.trace.bootstrap.instrumentation.httpurlconnection;

import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;

public class HttpUrlConnectionDecorator extends HttpClientDecorator<HttpURLConnection, Integer> {

  public static final HttpUrlConnectionDecorator DECORATE = new HttpUrlConnectionDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"httpurlconnection"};
  }

  @Override
  protected String component() {
    return "http-url-connection";
  }

  @Override
  protected String method(final HttpURLConnection connection) {
    return connection.getRequestMethod();
  }

  @Override
  protected URI url(final HttpURLConnection connection) throws URISyntaxException {
    return connection.getURL().toURI();
  }

  @Override
  protected int status(final Integer status) {
    return status;
  }
}
