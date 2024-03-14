package datadog.trace.instrumentation.jetty_client;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import org.eclipse.jetty.client.api.Request;

public class HeadersInjectAdapter implements AgentPropagation.Setter<Request> {

  public static final HeadersInjectAdapter SETTER = new HeadersInjectAdapter();

  @Override
  public void set(final Request carrier, final String key, final String value) {
    carrier.header(key, value);
  }
}
