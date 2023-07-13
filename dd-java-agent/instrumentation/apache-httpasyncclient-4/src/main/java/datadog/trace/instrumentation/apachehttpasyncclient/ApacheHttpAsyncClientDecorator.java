package datadog.trace.instrumentation.apachehttpasyncclient;

import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;
import org.apache.http.RequestLine;
import org.apache.http.StatusLine;
import org.apache.http.client.methods.HttpUriRequest;
import org.apache.http.protocol.HttpContext;
import org.apache.http.protocol.HttpCoreContext;

public class ApacheHttpAsyncClientDecorator extends HttpClientDecorator<HttpRequest, HttpContext> {

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
  protected String method(final HttpRequest request) {
    if (request instanceof HttpUriRequest) {
      return ((HttpUriRequest) request).getMethod();
    } else {
      final RequestLine requestLine = request.getRequestLine();
      return requestLine == null ? null : requestLine.getMethod();
    }
  }

  @Override
  protected URI url(final HttpRequest request) throws URISyntaxException {
    /*
     * Note: this is essentially an optimization: HttpUriRequest allows quicker access to required information.
     * The downside is that we need to load HttpUriRequest which essentially means we depend on httpasyncclient
     * library depending on httpclient library. Currently this seems to be the case.
     */
    if (request instanceof HttpUriRequest) {
      return ((HttpUriRequest) request).getURI();
    } else {
      final RequestLine requestLine = request.getRequestLine();
      return requestLine == null ? null : URIUtils.safeParse(requestLine.getUri());
    }
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
  protected String getRequestHeader(HttpRequest request, String headerName) {
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
