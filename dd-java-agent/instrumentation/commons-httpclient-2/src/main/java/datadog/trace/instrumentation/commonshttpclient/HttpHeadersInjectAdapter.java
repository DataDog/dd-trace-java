package datadog.trace.instrumentation.commonshttpclient;

import datadog.context.propagation.CarrierSetter;
import javax.annotation.ParametersAreNonnullByDefault;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpMethod;

@ParametersAreNonnullByDefault
public class HttpHeadersInjectAdapter implements CarrierSetter<HttpMethod> {

  public static final HttpHeadersInjectAdapter SETTER = new HttpHeadersInjectAdapter();

  @Override
  public void set(final HttpMethod carrier, final String key, final String value) {
    carrier.setRequestHeader(new Header(key, value));
  }
}
