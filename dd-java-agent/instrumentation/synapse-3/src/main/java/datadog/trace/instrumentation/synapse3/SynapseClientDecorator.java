package datadog.trace.instrumentation.synapse3;

import static datadog.trace.api.cache.RadixTreeCache.UNSET_STATUS;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;

public final class SynapseClientDecorator extends HttpClientDecorator<HttpRequest, HttpResponse> {
  public static final SynapseClientDecorator DECORATE = new SynapseClientDecorator();
  public static final CharSequence SYNAPSE_CLIENT = UTF8BytesString.create("synapse-client");
  public static final CharSequence SYNAPSE_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());
  public static final String SYNAPSE_SPAN_KEY = "dd.trace.synapse.span";
  public static final String SYNAPSE_CONTINUATION_KEY = "dd.trace.synapse.continuation";

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"synapse3"};
  }

  @Override
  protected CharSequence component() {
    return SYNAPSE_CLIENT;
  }

  @Override
  protected String method(final HttpRequest request) {
    return request.getRequestLine().getMethod();
  }

  @Override
  protected URI url(final HttpRequest request) {
    return URI.create(request.getRequestLine().getUri());
  }

  @Override
  protected int status(final HttpResponse response) {
    if (null != response.getStatusLine()) {
      return response.getStatusLine().getStatusCode();
    }
    return UNSET_STATUS;
  }

  @Override
  protected String getRequestHeader(HttpRequest request, String headerName) {
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
  protected String getResponseHeader(HttpResponse response, String headerName) {
    Header[] headers = response.getHeaders(headerName);
    List<String> values = new ArrayList<>();
    if (null != headers) {
      for (Header header : headers) {
        values.add(header.getValue());
      }
      return String.join(", ", values);
    }
    return null;
  }
}
