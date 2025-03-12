package datadog.trace.instrumentation.apachehttpclient5;

import datadog.context.propagation.CarrierSetter;
import javax.annotation.ParametersAreNonnullByDefault;
import org.apache.hc.core5.http.HttpRequest;

@ParametersAreNonnullByDefault
public class HttpHeadersInjectAdapter implements CarrierSetter<HttpRequest> {

  public static final HttpHeadersInjectAdapter SETTER = new HttpHeadersInjectAdapter();

  @Override
  public void set(final HttpRequest carrier, final String key, final String value) {
    carrier.setHeader(key, value);
  }
}
