package datadog.trace.core.propagation

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation

class MapSetter implements AgentPropagation.Setter<Map<String, String>> {
  static final INSTANCE = new MapSetter()

  @Override
  void set(Map<String, String> carrier, String key, String value) {
    carrier.put(key, value)
  }
}
