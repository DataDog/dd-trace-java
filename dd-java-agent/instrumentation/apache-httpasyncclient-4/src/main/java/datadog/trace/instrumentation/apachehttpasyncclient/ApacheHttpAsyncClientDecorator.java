package datadog.trace.instrumentation.apachehttpasyncclient;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;

public class ApacheHttpAsyncClientDecorator
    extends HttpClientDecorator<HttpUriRequest, HttpContext> {

  public static final CharSequence APACHE_HTTPASYNCCLIENT =
      UTF8BytesString.create("apache-httpasyncclient");

  public static final ApacheHttpAsyncClientDecorator DECORATE =
      new ApacheHttpAsyncClientDecorator();
  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"httpasyncclient", "apache-httpasyncclient"};
  }

  @Override
  protected CharSequence component() {
    return APACHE_HTTPASYNCCLIENT;
  }

  @Override
  protected String method(final HttpUriRequest request) {
    return request.getMethod();
  }

  @Override
  protected URI url(final HttpUriRequest request) throws URISyntaxException {
    return request.getURI();
  }

  @Override
  protected URI sourceUrl(final HttpUriRequest request) {
    return request.getURI();
  }

  @Override
  protected int status(final HttpContext context) {
    final Object responseObject = context.getAttribute(HttpCoreContext.HTTP_RESPONSE);
    if (responseObject instanceof HttpResponse) {
      final StatusLine statusLine = ((HttpResponse) responseObject).getStatusLine();
      if (statusLine != null) {
        return statusLine.getStatusCode();
      }
    }
    return 0;
  }

  @Override
  protected String getRequestHeader(HttpUriRequest request, String headerName) {
    Header header = request.getFirstHeader(headerName);
    if (header != null) {
      return header.getValue();
    }
    return null;
  }

  @Override
  protected String getResponseHeader(HttpContext context, String headerName) {
    final Object responseObject = context.getAttribute(HttpCoreContext.HTTP_RESPONSE);
    if (responseObject instanceof HttpResponse) {
      Header header = ((HttpResponse) responseObject).getFirstHeader(headerName);
      if (header != null) {
        return header.getValue();
      }
    }
    return null;
  }
}
