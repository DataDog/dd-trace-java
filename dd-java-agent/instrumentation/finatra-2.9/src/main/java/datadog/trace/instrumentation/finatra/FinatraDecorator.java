package datadog.trace.instrumentation.finatra;

import com.twitter.finagle.http.Request;
import com.twitter.finagle.http.Response;
import datadog.trace.bootstrap.instrumentation.api.DefaultURIDataAdapter;
import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import java.net.URI;

public class FinatraDecorator extends HttpServerDecorator<Request, Request, Response> {
  public static final FinatraDecorator DECORATE = new FinatraDecorator();

  @Override
  protected String component() {
    return "finatra";
  }

  @Override
  protected String method(final Request request) {
    return request.method().name();
  }

  @Override
  protected URIDataAdapter url(final Request request) {
    return new DefaultURIDataAdapter(URI.create(request.uri()));
  }

  @Override
  protected String peerHostIP(final Request request) {
    return request.remoteAddress().getHostAddress();
  }

  @Override
  protected Integer peerPort(final Request request) {
    return request.remotePort();
  }

  @Override
  protected Integer status(final Response response) {
    return response.statusCode();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"finatra"};
  }
}
