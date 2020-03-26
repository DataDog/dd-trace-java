package datadog.trace.instrumentation.playws;

import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;
import play.shaded.ahc.org.asynchttpclient.Request;
import play.shaded.ahc.org.asynchttpclient.Response;

public class PlayWSClientDecorator extends HttpClientDecorator<Request, Response> {
  public static final PlayWSClientDecorator DECORATE = new PlayWSClientDecorator();

  @Override
  protected String method(final Request request) {
    return request.getMethod();
  }

  @Override
  protected URI url(final Request request) throws URISyntaxException {
    return request.getUri().toJavaNetURI();
  }

  @Override
  protected Integer status(final Response response) {
    return response.getStatusCode();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"play-ws"};
  }

  @Override
  protected String component() {
    return "play-ws";
  }
}
