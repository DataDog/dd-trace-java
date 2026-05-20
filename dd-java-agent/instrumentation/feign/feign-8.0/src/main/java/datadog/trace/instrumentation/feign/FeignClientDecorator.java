package datadog.trace.instrumentation.feign;

import static datadog.context.Context.current;
import static datadog.trace.instrumentation.feign.RequestInjectAdapter.SETTER;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import feign.Request;
import feign.Response;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Map;

public class FeignClientDecorator extends HttpClientDecorator<Request, Response> {
  public static final CharSequence FEIGN = UTF8BytesString.create("feign");
  public static final FeignClientDecorator DECORATE = new FeignClientDecorator();
  public static final CharSequence FEIGN_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"feign"};
  }

  @Override
  protected CharSequence component() {
    return FEIGN;
  }

  @Override
  protected String method(final Request request) {
    return request.method();
  }

  @Override
  protected URI url(final Request request) throws URISyntaxException {
    return new URI(request.url());
  }

  @Override
  protected int status(final Response response) {
    return response.status();
  }

  @Override
  protected String getRequestHeader(Request request, String headerName) {
    Collection<String> values = request.headers().get(headerName);
    if (values != null && !values.isEmpty()) {
      return values.iterator().next();
    }
    return null;
  }

  @Override
  protected String getResponseHeader(Response response, String headerName) {
    Collection<String> values = response.headers().get(headerName);
    if (values != null && !values.isEmpty()) {
      return values.iterator().next();
    }
    return null;
  }

  /** Inject trace headers into the Feign request headers map. */
  public void injectHeaders(Map<String, Collection<String>> headers) {
    injectContext(current(), headers, SETTER);
  }
}
