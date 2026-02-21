package datadog.trace.instrumentation.feign;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import feign.Request;
import feign.Response;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Map;

public class FeignDecorator extends HttpClientDecorator<Request, Response> {

  public static final CharSequence FEIGN = UTF8BytesString.create("feign");
  public static final FeignDecorator DECORATE = new FeignDecorator();
  public static final CharSequence FEIGN_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"feign"};
  }

  @Override
  protected String service() {
    return null;
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
    Map<String, Collection<String>> headers = request.headers();
    if (headers != null) {
      for (Map.Entry<String, Collection<String>> entry : headers.entrySet()) {
        if (entry.getKey().equalsIgnoreCase(headerName)) {
          Collection<String> values = entry.getValue();
          if (values != null && !values.isEmpty()) {
            return values.iterator().next();
          }
        }
      }
    }
    return null;
  }

  @Override
  protected String getResponseHeader(Response response, String headerName) {
    Map<String, Collection<String>> headers = response.headers();
    if (headers != null) {
      Collection<String> values = headers.get(headerName);
      if (values != null && !values.isEmpty()) {
        return values.iterator().next();
      }
    }
    return null;
  }
}
