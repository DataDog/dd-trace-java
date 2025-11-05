package datadog.trace.instrumentation.playws;

import datadog.context.propagation.CarrierSetter;
import javax.annotation.ParametersAreNonnullByDefault;
import play.shaded.ahc.org.asynchttpclient.Request;

@ParametersAreNonnullByDefault
public class HeadersInjectAdapter implements CarrierSetter<Request> {

  public static final HeadersInjectAdapter SETTER = new HeadersInjectAdapter();

  @Override
  public void set(final Request carrier, final String key, final String value) {
    carrier.getHeaders().add(key, value);
  }
}
