package datadog.trace.instrumentation.apachehttpclient;

import datadog.trace.instrumentation.api.Propagation;
import org.apache.http.client.methods.HttpUriRequest;

public class ApacheHttpClientSetter implements Propagation.Setter<HttpUriRequest> {
  public static final ApacheHttpClientSetter SETTER = new ApacheHttpClientSetter();

  @Override
  public void set(final HttpUriRequest carrier, final String key, final String value) {
    carrier.addHeader(key, value);
  }
}
