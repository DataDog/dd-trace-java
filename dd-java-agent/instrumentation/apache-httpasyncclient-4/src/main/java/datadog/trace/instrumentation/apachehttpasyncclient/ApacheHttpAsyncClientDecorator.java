package datadog.trace.instrumentation.apachehttpasyncclient;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
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
    Header[] headers = request.getHeaders(headerName);
    List<String> values = new ArrayList<>();
    if (null != headers) {
      for (Header header : headers) {
        values.add(header.getValue());
      }
      return String.join(", ", values);
    }
    return null;
  }

  @Override
  protected String getResponseHeader(HttpContext context, String headerName) {
    final Object responseObject = context.getAttribute(HttpCoreContext.HTTP_RESPONSE);
    if (responseObject instanceof HttpResponse) {
      Header[] headers = ((HttpResponse) responseObject).getHeaders(headerName);
      List<String> values = new ArrayList<>();
      if (null != headers) {
        for (Header header : headers) {
          values.add(header.getValue());
        }
        return String.join(", ", values);
      }
    }
    return null;
  }
}
