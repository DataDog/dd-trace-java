package datadog.trace.instrumentation.mulehttpconnector;

import com.ning.http.client.FluentCaseInsensitiveStringsMap;
import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

public class HttpRequesterResponseInjectAdapter
    implements AgentPropagation.Setter<FluentCaseInsensitiveStringsMap> {

  public static final HttpRequesterResponseInjectAdapter SETTER =
      new HttpRequesterResponseInjectAdapter();

  @Override
  public void set(
      final FluentCaseInsensitiveStringsMap headers, final String key, final String value) {
    headers.add(key, value);
  }
}
