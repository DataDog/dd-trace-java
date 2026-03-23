package datadog.trace.instrumentation.commonshttpclient;

import datadog.trace.bootstrap.instrumentation.api.AgentSpan;
import datadog.trace.bootstrap.instrumentation.api.URIUtils;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.StatusLine;

public class CommonsHttpClientDecorator extends HttpClientDecorator<HttpMethod, HttpMethod> {
  public static final CharSequence COMMONS_HTTP_CLIENT =
      UTF8BytesString.create("commons-httpclient");
  public static final CommonsHttpClientDecorator DECORATE = new CommonsHttpClientDecorator();
  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String method(final HttpMethod httpMethod) {
    return httpMethod.getName();
  }

  @Override
  protected URI url(final HttpMethod httpMethod) throws URISyntaxException {
    try {
      // commons-httpclient uses getURI() which returns a URI object
      return URIUtils.safeParse(httpMethod.getURI().toString());
    } catch (final Exception e) {
      return null;
    }
  }

  public AgentSpan prepareSpan(AgentSpan span, HttpMethod request) {
    DECORATE.afterStart(span);
    DECORATE.onRequest(span, request);
    return span;
  }

  @Override
  protected int status(final HttpMethod httpMethod) {
    final StatusLine statusLine = httpMethod.getStatusLine();
    return statusLine == null ? 0 : statusLine.getStatusCode();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"commons-httpclient"};
  }

  @Override
  protected CharSequence component() {
    return COMMONS_HTTP_CLIENT;
  }

  @Override
  protected String getRequestHeader(HttpMethod request, String headerName) {
    final Header header = request.getRequestHeader(headerName);
    return header != null ? header.getValue() : null;
  }

  @Override
  protected String getResponseHeader(HttpMethod response, String headerName) {
    final Header header = response.getResponseHeader(headerName);
    return header != null ? header.getValue() : null;
  }
}
