package datadog.trace.instrumentation.apachehttpclient;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.apache.http.client.methods.HttpUriRequest;

public final class HttpHeadersInjectAdapter implements AgentPropagation.Setter<HttpUriRequest> {

  public static final HttpHeadersInjectAdapter SETTER = new HttpHeadersInjectAdapter();

  @Override
  public void set(final HttpUriRequest carrier, final String key, final String value) {
    carrier.setHeader(key, value);
  }
}
