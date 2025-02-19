package datadog.trace.instrumentation.jaxrs;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.ws.rs.core.MultivaluedMap;

@ParametersAreNonnullByDefault
public final class InjectAdapter
    implements AgentPropagation.Setter<MultivaluedMap<String, Object>> {

  public static final InjectAdapter SETTER = new InjectAdapter();

  @Override
  public void set(
      final MultivaluedMap<String, Object> headers, final String key, final String value) {
    // Don't allow duplicates.
    headers.putSingle(key, value);
  }
}
