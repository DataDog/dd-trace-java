package datadog.trace.instrumentation.jetty_client12;

import datadog.context.propagation.CarrierSetter;
import org.eclipse.jetty.client.Request;

public class HeadersInjectAdapter implements CarrierSetter<Request> {

  public static final HeadersInjectAdapter SETTER = new HeadersInjectAdapter();

  @Override
  public void set(final Request carrier, final String key, final String value) {
    carrier.headers(httpFields -> httpFields.add(key, value));
  }
}
