package datadog.trace.instrumentation.grizzly.client;

import static datadog.trace.bootstrap.instrumentation.api.DDComponents.GRIZZLY_HTTP_ASYNC_CLIENT;

import com.ning.http.client.Request;
import com.ning.http.client.Response;
import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;

public class ClientDecorator extends HttpClientDecorator<Request, Response> {

  public static final CharSequence HTTP_REQUEST = UTF8BytesString.createConstant("http.request");

  public static final ClientDecorator DECORATE = new ClientDecorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"grizzly-client", "ning"};
  }

  @Override
  protected String component() {
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
}
