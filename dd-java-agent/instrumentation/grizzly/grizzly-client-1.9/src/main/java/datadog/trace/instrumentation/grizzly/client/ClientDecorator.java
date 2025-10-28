package datadog.trace.instrumentation.grizzly.client;

import com.ning.http.client.Request;
import com.ning.http.client.Response;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;

public class ClientDecorator extends HttpClientDecorator<Request, Response> {

  private static final CharSequence GRIZZLY_HTTP_ASYNC_CLIENT =
      UTF8BytesString.create("grizzly-http-async-client");
  public static final ClientDecorator DECORATE = new ClientDecorator();

  public static final CharSequence HTTP_REQUEST = UTF8BytesString.create(DECORATE.operationName());

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"grizzly-client", "ning"};
  }

  @Override
  protected CharSequence component() {
    return GRIZZLY_HTTP_ASYNC_CLIENT;
  }

  @Override
  protected String method(final Request request) {
    return request.getMethod();
  }

  @Override
  protected URI url(final Request request) throws URISyntaxException {
    return request.getUri().toJavaNetURI();
  }

  @Override
  protected int status(final Response response) {
    return response.getStatusCode();
  }

  @Override
  protected String getRequestHeader(Request request, String headerName) {
    return request.getHeaders().getFirstValue(headerName);
  }

  @Override
  protected String getResponseHeader(Response response, String headerName) {
    return response.getHeader(headerName);
  }
}
