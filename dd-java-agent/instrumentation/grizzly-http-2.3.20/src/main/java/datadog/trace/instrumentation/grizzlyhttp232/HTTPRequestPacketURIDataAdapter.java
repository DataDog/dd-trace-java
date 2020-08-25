package datadog.trace.instrumentation.grizzlyhttp232;

import datadog.trace.bootstrap.instrumentation.api.URIDataAdapter;
import java.nio.charset.StandardCharsets;
import org.glassfish.grizzly.http.HttpRequestPacket;

final class HTTPRequestPacketURIDataAdapter implements URIDataAdapter {

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
  public String path() {
    return packet.getRequestURI();
  }

  @Override
  public String fragment() {
    return "";
  }

  @Override
  public String query() {
    return packet.getQueryString() != null ? packet.getQueryString() : "";
  }
}
