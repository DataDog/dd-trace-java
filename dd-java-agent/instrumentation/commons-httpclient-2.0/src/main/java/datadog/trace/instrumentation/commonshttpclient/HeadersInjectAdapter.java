package datadog.trace.instrumentation.commonshttpclient;

import datadog.context.propagation.CarrierSetter;
import javax.annotation.ParametersAreNonnullByDefault;
import org.apache.commons.httpclient.HttpMethod;

@ParametersAreNonnullByDefault
public class HeadersInjectAdapter implements CarrierSetter<HttpMethod> {

  public static final HeadersInjectAdapter SETTER = new HeadersInjectAdapter();

  @Override
  public void set(final HttpMethod carrier, final String key, final String value) {
    carrier.setRequestHeader(key, value);
  }
}
