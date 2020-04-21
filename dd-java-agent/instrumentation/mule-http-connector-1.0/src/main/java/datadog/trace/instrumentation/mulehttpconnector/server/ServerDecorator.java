package datadog.trace.instrumentation.mulehttpconnector.server;

import datadog.trace.bootstrap.instrumentation.decorator.HttpServerDecorator;
import org.glassfish.grizzly.http.HttpRequestPacket;
import org.glassfish.grizzly.http.HttpResponsePacket;

import java.net.URI;
import java.net.URISyntaxException;

public class ServerDecorator
    extends HttpServerDecorator<HttpRequestPacket, HttpRequestPacket, HttpResponsePacket> {

  public static final ServerDecorator DECORATE = new ServerDecorator();

  @Override
  protected String method(final HttpRequestPacket httpRequest) {
    return httpRequest.getMethod().getMethodString();
  }

  @Override
  protected URI url(final HttpRequestPacket httpRequest) throws URISyntaxException {
    return new URI(
        (httpRequest.isSecure() ? "https://" : "http://")
            + httpRequest.getRemoteHost()
            + ":"
            + httpRequest.getLocalPort()
            + httpRequest.getRequestURI()
            + (httpRequest.getQueryString() != null ? "?" + httpRequest.getQueryString() : ""));
  }

  @Override
  protected String peerHostIP(final HttpRequestPacket httpRequest) {
    return httpRequest.getLocalHost();
  }

  @Override
  protected Integer peerPort(final HttpRequestPacket httpRequest) {
    return httpRequest.getLocalPort();
  }

  @Override
  protected Integer status(final HttpResponsePacket httpResponse) {
    return httpResponse.getStatus();
  }

  @Override
  protected String[] instrumentationNames() {
    return new String[] {"mule-http-connector"};
  }

  @Override
  protected String component() {
    return "grizzly-filterchain-server";
  }
}
