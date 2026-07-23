package datadog.trace.instrumentation.quarkus_rest_client_reactive_javax;

import datadog.context.propagation.CarrierSetter;
import javax.annotation.ParametersAreNonnullByDefault;
import javax.ws.rs.core.MultivaluedMap;

@ParametersAreNonnullByDefault
public final class InjectAdapter implements CarrierSetter<MultivaluedMap<String, Object>> {

  public static final InjectAdapter SETTER = new InjectAdapter();

  @Override
  public void set(
      final MultivaluedMap<String, Object> headers, final String key, final String value) {
    headers.putSingle(key, value);
  }
}
