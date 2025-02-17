package datadog.trace.instrumentation.apachehttpclient;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import javax.annotation.ParametersAreNonnullByDefault;
import org.apache.http.client.methods.HttpUriRequest;

@ParametersAreNonnullByDefault
public class HttpHeadersInjectAdapter implements AgentPropagation.Setter<HttpUriRequest> {

  public static final HttpHeadersInjectAdapter SETTER = new HttpHeadersInjectAdapter();

  @Override
  public void set(final HttpUriRequest carrier, final String key, final String value) {
    carrier.setHeader(key, value);
  }
}
