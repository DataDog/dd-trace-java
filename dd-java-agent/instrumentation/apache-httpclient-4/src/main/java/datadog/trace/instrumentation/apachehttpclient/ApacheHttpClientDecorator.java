package datadog.trace.instrumentation.apachehttpclient;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.client.methods.HttpUriRequest;

public class ApacheHttpClientDecorator extends HttpClientDecorator<HttpUriRequest, HttpResponse> {

  public static final CharSequence APACHE_HTTP_CLIENT = UTF8BytesString.create("apache-httpclient");
  public static final ApacheHttpClientDecorator DECORATE = new ApacheHttpClientDecorator();

  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"httpclient", "apache-httpclient", "apache-http-client"};
  }

  @Override
  protected CharSequence component() {
    return APACHE_HTTP_CLIENT;
  }

  @Override
  protected String method(final HttpUriRequest httpRequest) {
    return httpRequest.getMethod();
  }

  @Override
  protected URI url(final HttpUriRequest request) {
    return request.getURI();
  }

  @Override
  protected URI sourceUrl(final HttpUriRequest request) {
    return request.getURI();
  }

  @Override
  protected int status(final HttpResponse httpResponse) {
    return httpResponse.getStatusLine().getStatusCode();
  }

  @Override
  protected String getRequestHeader(HttpUriRequest request, String headerName) {
    Header[] headers = request.getHeaders(headerName);
    if (headers.length > 0) {
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < headers.length; i++) {
        result.append(headers[i].getValue());
        if (i + 1 < headers.length) {
          result.append(",");
        }
      }
      return result.toString();
    }
    return null;
  }

  @Override
  protected String getResponseHeader(HttpResponse response, String headerName) {
    Header[] headers = response.getHeaders(headerName);
    if (headers.length > 0) {
      StringBuilder result = new StringBuilder();
      for (int i = 0; i < headers.length; i++) {
        result.append(headers[i].getValue());
        if (i + 1 < headers.length) {
          result.append(",");
        }
      }
      return result.toString();
    }
    return null;
  }
}
