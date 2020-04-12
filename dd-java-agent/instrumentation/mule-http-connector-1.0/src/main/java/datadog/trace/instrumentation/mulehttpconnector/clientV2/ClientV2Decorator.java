package datadog.trace.instrumentation.mulehttpconnector.clientV2;

import datadog.trace.bootstrap.instrumentation.decorator.HttpClientDecorator;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;

import java.net.URI;
import java.net.URISyntaxException;

public class ClientV2Decorator extends HttpClientDecorator<HttpRequestPacket, HttpResponsePacket> {

  public static final ClientV2Decorator DECORATE = new ClientV2Decorator();

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"mule-http-connector"};
  }

  @Override
  protected String component() {
    return "http-requester-clientV2";
  }

  @Override
  protected String method(final HttpRequestPacket request) {
    return request.getMethod().getMethodString();
  }

  @Override
  protected URI url(final HttpRequestPacket request) throws URISyntaxException {
    return new URI(request.getRequestURI());
  }

  @Override
  protected Integer status(final HttpResponsePacket response) {
    return response.getStatus();
  }
}
