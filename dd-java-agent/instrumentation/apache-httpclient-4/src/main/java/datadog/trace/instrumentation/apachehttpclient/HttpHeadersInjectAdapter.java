package datadog.trace.instrumentation.apachehttpclient;

import datadog.context.propagation.CarrierSetter;
import javax.annotation.ParametersAreNonnullByDefault;
import org.apache.http.client.methods.HttpUriRequest;

@ParametersAreNonnullByDefault
public class HttpHeadersInjectAdapter implements CarrierSetter<HttpUriRequest> {

  public static final HttpHeadersInjectAdapter SETTER = new HttpHeadersInjectAdapter();

  @Override
  public void set(final HttpUriRequest carrier, final String key, final String value) {
    carrier.setHeader(key, value);
  }
}
