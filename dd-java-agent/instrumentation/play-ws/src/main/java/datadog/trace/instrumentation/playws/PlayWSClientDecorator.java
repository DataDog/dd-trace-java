package datadog.trace.instrumentation.playws;

import datadog.trace.bootstrap.instrumentation.api.UTF8BytesString;
import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import java.net.URI;
import java.net.URISyntaxException;
import play.shaded.ahc.org.asynchttpclient.Request;
import play.shaded.ahc.org.asynchttpclient.Response;

public class PlayWSClientDecorator extends HttpClientDecorator<Request, Response> {
  public static final CharSequence PLAY_WS = UTF8BytesString.create("play-ws");
  public static final PlayWSClientDecorator DECORATE = new PlayWSClientDecorator();
  public static final CharSequence PLAY_WS_REQUEST =
      UTF8BytesString.create(DECORATE.operationName());

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
  protected String[] instrumentationNames() {
    return new String[] {"play-ws"};
  }

  @Override
  protected CharSequence component() {
    return PLAY_WS;
  }

  @Override
  protected String getRequestHeader(Request request, String headerName) {
    return request.getHeaders().get(headerName);
  }

  @Override
  protected String getResponseHeader(Response response, String headerName) {
    return response.getHeaders().get(headerName);
  }
}
