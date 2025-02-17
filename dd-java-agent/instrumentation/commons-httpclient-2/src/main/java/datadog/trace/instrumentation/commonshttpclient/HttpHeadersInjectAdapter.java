package datadog.trace.instrumentation.commonshttpclient;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import javax.annotation.ParametersAreNonnullByDefault;
import org.apache.commons.httpclient.Header;
import org.apache.commons.httpclient.HttpMethod;

@ParametersAreNonnullByDefault
public class HttpHeadersInjectAdapter implements AgentPropagation.Setter<HttpMethod> {

  public static final HttpHeadersInjectAdapter SETTER = new HttpHeadersInjectAdapter();

  @Override
  public void set(final HttpMethod carrier, final String key, final String value) {
    carrier.setRequestHeader(new Header(key, value));
  }
}
