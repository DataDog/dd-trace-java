package datadog.trace.instrumentation.jetty_client;

import datadog.context.propagation.CarrierSetter;
import javax.annotation.ParametersAreNonnullByDefault;
import org.eclipse.jetty.client.api.Request;

@ParametersAreNonnullByDefault
public class HeadersInjectAdapter implements CarrierSetter<Request> {

  public static final HeadersInjectAdapter SETTER = new HeadersInjectAdapter();

  @Override
  public void set(final Request carrier, final String key, final String value) {
    carrier.header(key, value);
  }
}
