package datadog.trace.instrumentation.okhttp2;

import com.squareup.okhttp.Request;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

public class RequestBuilderInjectAdapter implements AgentPropagation.Setter<Request.Builder> {
  public static final RequestBuilderInjectAdapter SETTER = new RequestBuilderInjectAdapter();

  @Override
  public void set(final Request.Builder carrier, final String key, final String value) {
    carrier.addHeader(key, value);
  }
}
