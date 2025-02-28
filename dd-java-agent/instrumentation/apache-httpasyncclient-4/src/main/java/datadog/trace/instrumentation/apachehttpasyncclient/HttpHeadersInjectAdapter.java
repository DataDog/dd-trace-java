package datadog.trace.instrumentation.apachehttpasyncclient;

import datadog.context.propagation.CarrierSetter;
import javax.annotation.ParametersAreNonnullByDefault;
import org.apache.http.HttpRequest;

@ParametersAreNonnullByDefault
public class HttpHeadersInjectAdapter implements CarrierSetter<HttpRequest> {

  public static final HttpHeadersInjectAdapter SETTER = new HttpHeadersInjectAdapter();

  @Override
  public void set(final HttpRequest carrier, final String key, final String value) {
    carrier.setHeader(key, value);
  }
}
