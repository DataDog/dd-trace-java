package datadog.trace.instrumentation.grizzlyhttp232;

import datadog.trace.bootstrap.instrumentation.api.URIRawDataAdapter;
import java.nio.charset.StandardCharsets;
import org.glassfish.grizzly.http.HttpRequestPacket;

final class HTTPRequestPacketURIDataAdapter extends URIRawDataAdapter {

  private final HttpRequestPacket packet;

  HTTPRequestPacketURIDataAdapter(HttpRequestPacket packet) {
    this.packet = packet;
  }

  @Override
  public String scheme() {
    return packet.isSecure() ? "https" : "http";
  }

  @Override
  public String host() {
    return packet.serverName().toString(StandardCharsets.UTF_8);
  }

  @Override
  public int port() {
    return packet.getServerPort();
  }

  @Override
  protected String innerRawPath() {
    return packet.getRequestURI();
  }

  @Override
  public String fragment() {
    return null;
  }

  @Override
  protected String innerRawQuery() {
    return packet.getQueryString();
  }
}
