package datadog.trace.instrumentation.googlehttpclient;

import com.google.api.client.http.HttpRequest;
import datadog.context.propagation.CarrierSetter;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class HeadersInjectAdapter implements CarrierSetter<HttpRequest> {

  public static final HeadersInjectAdapter SETTER = new HeadersInjectAdapter();

  @Override
  public void set(final HttpRequest carrier, final String key, final String value) {
    carrier.getHeaders().put(key, value);
  }
}
