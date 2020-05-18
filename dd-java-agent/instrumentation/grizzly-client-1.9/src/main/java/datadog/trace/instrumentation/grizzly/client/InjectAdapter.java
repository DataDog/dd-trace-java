package datadog.trace.instrumentation.grizzly.client;

import com.ning.http.client.Request;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

public class InjectAdapter implements AgentPropagation.Setter<Request> {

  public static final InjectAdapter SETTER = new InjectAdapter();

  @Override
  public void set(final Request carrier, final String key, final String value) {
    carrier.getHeaders().replaceWith(key, value);
  }
}
