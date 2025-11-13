package datadog.trace.instrumentation.synapse3;

import static datadog.trace.api.cache.RadixTreeCache.UNSET_STATUS;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import org.apache.http.Header;
import org.apache.http.HttpRequest;
import org.apache.http.HttpResponse;

public final class SynapseClientDecorator extends HttpClientDecorator<HttpRequest, HttpResponse> {
  public static final SynapseClientDecorator DECORATE = new SynapseClientDecorator();
  public static final CharSequence SYNAPSE_CLIENT = UTF8BytesString.create("synapse-client");
  public static final CharSequence SYNAPSE_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());
  public static final String SYNAPSE_CONTEXT_KEY = "dd.trace.synapse.context";
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
    Header header = request.getFirstHeader(headerName);
    if (null != header) {
      return header.getValue();
    }
    return null;
  }

  @Override
  protected String getResponseHeader(HttpResponse response, String headerName) {
    Header header = response.getFirstHeader(headerName);
    if (null != header) {
      return header.getValue();
    }
    return null;
  }
}
