package datadog.trace.instrumentation.feign;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import feign.Request;
import feign.Response;
import java.net.URI;
import java.net.URISyntaxException;

public class FeignClientDecorator extends HttpClientDecorator<Request, Response> {

  public static final CharSequence FEIGN_CLIENT = UTF8BytesString.create("feign-client");

  public static final FeignClientDecorator DECORATE = new FeignClientDecorator();
  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"feign"};
  }

  @Override
  protected CharSequence component() {
    return FEIGN_CLIENT;
  }

  @Override
  protected String method(final Request request) {
    return request.httpMethod().name();
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
    if (request.headers() != null && request.headers().containsKey(headerName)) {
      return String.join(",", request.headers().get(headerName));
    }
    return null;
  }

  @Override
  protected String getResponseHeader(Response response, String headerName) {
    if (response.headers() != null && response.headers().containsKey(headerName)) {
      return String.join(",", response.headers().get(headerName));
    }
    return null;
  }
}
