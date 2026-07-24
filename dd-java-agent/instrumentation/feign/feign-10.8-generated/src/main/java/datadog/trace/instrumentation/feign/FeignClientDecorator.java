package datadog.trace.instrumentation.feign;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import feign.Request;
import feign.Response;
import java.net.URI;
import java.net.URISyntaxException;

public class FeignClientDecorator extends HttpClientDecorator<Request, Response> {

  public static final CharSequence FEIGN = UTF8BytesString.create("feign");
  public static final FeignClientDecorator DECORATE = new FeignClientDecorator();

  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"feign", "feign-10.8"};
  }

  @Override
  protected CharSequence component() {
    return FEIGN;
  }

  @Override
  protected String method(final Request request) {
    return request.httpMethod().name();
  }

  @Override
  protected URI url(final Request request) {
    try {
      return new URI(request.url());
    } catch (URISyntaxException e) {
      return null;
    }
  }

  @Override
  protected int status(final Response response) {
    return response.status();
  }

  @Override
  protected String getRequestHeader(Request request, String headerName) {
    java.util.Collection<String> values = request.headers().get(headerName);
    if (values != null && !values.isEmpty()) {
      return values.iterator().next();
    }
    return null;
  }

  @Override
  protected String getResponseHeader(Response response, String headerName) {
    java.util.Collection<String> values = response.headers().get(headerName);
    if (values != null && !values.isEmpty()) {
      return values.iterator().next();
    }
    return null;
  }
}
