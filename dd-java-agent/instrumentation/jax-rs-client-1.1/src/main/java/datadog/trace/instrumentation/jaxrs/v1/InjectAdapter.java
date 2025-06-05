package datadog.trace.instrumentation.jaxrs.v1;

import datadog.context.propagation.CarrierSetter;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.ws.rs.core.MultivaluedMap;

@ParametersAreNonnullByDefault
public final class InjectAdapter implements CarrierSetter<MultivaluedMap<String, Object>> {

  public static final InjectAdapter SETTER = new InjectAdapter();

  @Override
  public void set(
      final MultivaluedMap<String, Object> headers, final String key, final String value) {
    // Don't allow duplicates.
    headers.putSingle(key, value);
  }
}
