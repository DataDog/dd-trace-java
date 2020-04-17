package datadog.trace.instrumentation.mulehttpconnector.client;

import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

public class InjectAdapter implements AgentPropagation.Setter<FluentCaseInsensitiveStringsMap> {

  public static final InjectAdapter SETTER = new InjectAdapter();

  @Override
  public void set(
      final FluentCaseInsensitiveStringsMap headers, final String key, final String value) {
    headers.add(key, value);
  }
}
