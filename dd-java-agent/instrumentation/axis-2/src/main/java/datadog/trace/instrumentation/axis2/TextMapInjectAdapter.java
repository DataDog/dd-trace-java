package datadog.trace.instrumentation.axis2;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import java.util.Map;

public class TextMapInjectAdapter implements AgentPropagation.Setter<Map<String, Object>> {
  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(Map<String, Object> carrier, String key, String value) {
    carrier.put(key, value);
  }
}
