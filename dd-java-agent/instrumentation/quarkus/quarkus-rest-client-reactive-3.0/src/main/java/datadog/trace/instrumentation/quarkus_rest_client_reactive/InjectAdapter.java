package datadog.trace.instrumentation.quarkus_rest_client_reactive;

import datadog.context.propagation.CarrierSetter;
import jakarta.ws.rs.core.MultivaluedMap;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public final class InjectAdapter implements CarrierSetter<MultivaluedMap<String, Object>> {

  public static final InjectAdapter SETTER = new InjectAdapter();

  @Override
  public void set(
      final MultivaluedMap<String, Object> headers, final String key, final String value) {
    headers.putSingle(key, value);
  }
}
