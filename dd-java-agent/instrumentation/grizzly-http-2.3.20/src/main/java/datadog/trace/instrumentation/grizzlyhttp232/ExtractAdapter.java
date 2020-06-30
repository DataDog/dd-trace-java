package datadog.trace.instrumentation.grizzlyhttp232;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.glassfish.grizzly.http.HttpHeader;

public class ExtractAdapter implements AgentPropagation.Getter<HttpHeader> {
  public static final ExtractAdapter GETTER = new ExtractAdapter();

  @Override
  public Iterable<String> keys(final HttpHeader carrier) {
    return carrier.getHeaders().names();
  }

  @Override
  public String get(final HttpHeader carrier, final String key) {
    return carrier.getHeader(key);
  }
}
