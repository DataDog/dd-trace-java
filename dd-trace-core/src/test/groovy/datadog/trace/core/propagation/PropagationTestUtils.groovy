package datadog.trace.core.propagation

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation

class MapSetter implements AgentPropagation.Setter<Map<String, String>> {
  static final INSTANCE = new MapSetter()

  @Override
  void set(Map<String, String> carrier, String key, String value) {
    carrier.put(key, value)
  }
}

class MapGetter implements AgentPropagation.Getter<Map<String, String>> {
  static final INSTANCE = new MapGetter()
  
  @Override
  Iterable<String> keys(Map<String, String> carrier) {
    return carrier.keySet()
  }

  @Override
  String get(Map<String, String> carrier, String key) {
    return carrier.get(key)
  }
}
