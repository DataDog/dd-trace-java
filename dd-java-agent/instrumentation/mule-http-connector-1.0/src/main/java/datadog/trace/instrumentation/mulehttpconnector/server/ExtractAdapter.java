package datadog.trace.instrumentation.mulehttpconnector.server;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.glassfish.grizzly.http.HttpRequestPacket;

public class ExtractAdapter implements AgentPropagation.Getter<HttpRequestPacket> {
  public static final ExtractAdapter GETTER = new ExtractAdapter();

  @Override
  public Iterable<String> keys(final HttpRequestPacket carrier) {
    return carrier.getHeaders().names();
  }

  @Override
  public String get(final HttpRequestPacket carrier, final String key) {
    return carrier.getHeader(key);
  }
}
