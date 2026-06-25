package datadog.trace.instrumentation.commonshttpclient;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpMethod;
import org.apache.commons.httpclient.StatusLine;

public class CommonsHttpClientDecorator extends HttpClientDecorator<HttpMethod, HttpMethod> {

  public static final CharSequence COMMONS_HTTP_CLIENT =
      UTF8BytesString.create("commons-http-client");
  public static final CommonsHttpClientDecorator DECORATE = new CommonsHttpClientDecorator();

  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"commons-http-client"};
  }

  @Override
  protected CharSequence component() {
    return COMMONS_HTTP_CLIENT;
  }

  @Override
  protected String method(final HttpMethod httpMethod) {
    return httpMethod.getName();
  }

  @Override
  protected URI url(final HttpMethod httpMethod) throws URISyntaxException {
    try {
      org.apache.commons.httpclient.URI uri = httpMethod.getURI();
      if (uri != null) {
        return new URI(uri.toString());
      }
    } catch (Exception e) {
      // getURI() can throw URIException; fall through to return null
    }
    return null;
  }

  @Override
  protected HttpMethod sourceUrl(final HttpMethod httpMethod) {
    return httpMethod;
  }

  @Override
  protected int status(final HttpMethod httpMethod) {
    final StatusLine statusLine = httpMethod.getStatusLine();
    return statusLine == null ? 0 : statusLine.getStatusCode();
  }

  @Override
  protected String getRequestHeader(HttpMethod httpMethod, String headerName) {
    Header header = httpMethod.getRequestHeader(headerName);
    if (header != null) {
      return header.getValue();
    }
    return null;
  }

  @Override
  protected String getResponseHeader(HttpMethod httpMethod, String headerName) {
    Header header = httpMethod.getResponseHeader(headerName);
    if (header != null) {
      return header.getValue();
    }
    return null;
  }
}
