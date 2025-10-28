package datadog.trace.instrumentation.grizzly.client;

import com.ning.http.client.Request;
import datadog.context.propagation.CarrierSetter;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class InjectAdapter implements CarrierSetter<Request> {
  public static final InjectAdapter SETTER = new InjectAdapter();

  @Override
  public void set(final Request carrier, final String key, final String value) {
    carrier.getHeaders().replaceWith(key, value);
  }
}
