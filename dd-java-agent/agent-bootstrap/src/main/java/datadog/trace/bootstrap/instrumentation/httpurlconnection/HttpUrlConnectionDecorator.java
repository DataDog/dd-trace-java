package datadog.trace.bootstrap.instrumentation.httpurlconnection;

import static datadog.trace.api.cache.RadixTreeCache.UNSET_STATUS;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;

public class HttpUrlConnectionDecorator
    extends HttpClientDecorator<HttpURLConnection, HttpURLConnection> {

  public static final CharSequence HTTP_URL_CONNECTION =
      UTF8BytesString.create("http-url-connection");

  public static final HttpUrlConnectionDecorator DECORATE = new HttpUrlConnectionDecorator();

  public static final CharSequence OPERATION_NAME =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"httpurlconnection"};
  }

  @Override
  protected CharSequence component() {
    return HTTP_URL_CONNECTION;
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
  protected int status(final HttpURLConnection connection) {
    try {
      return connection.getResponseCode();
    } catch (Exception ignored) {
      return UNSET_STATUS;
    }
  }

  @Override
  protected String getRequestHeader(HttpURLConnection connection, String headerName) {
    return connection.getRequestProperty(headerName);
  }

  @Override
  protected String getResponseHeader(HttpURLConnection connection, String headerName) {
    return connection.getHeaderField(headerName);
  }
}
