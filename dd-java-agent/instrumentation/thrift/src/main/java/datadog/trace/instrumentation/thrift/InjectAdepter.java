package datadog.trace.instrumentation.thrift;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

import java.util.Map;

public class InjectAdepter implements AgentPropagation.Setter<Map<String, String>> {

  public static final InjectAdepter SETTER = new InjectAdepter();

  @Override
  public void set(final Map<String, String> carrier, final String key, final String value) {
    carrier.put(key, value);
  }
}
