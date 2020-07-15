package datadog.trace.core.propagation

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation

import static datadog.trace.bootstrap.instrumentation.api.AgentPropagation.KeyClassifier.IGNORE

class MapSetter implements AgentPropagation.Setter<Map<String, String>> {
  static final INSTANCE = new MapSetter()

  @Override
  void set(Map<String, String> carrier, String key, String value) {
    carrier.put(key, value)
  }
}

class MapGetter implements AgentPropagation.ContextVisitor<Map<String, String>> {
  static final INSTANCE = new MapGetter()

  @Override
  void forEachKey(Map<String, String> carrier,
                  AgentPropagation.KeyClassifier classifier,
                  AgentPropagation.KeyValueConsumer consumer) {
    for (Map.Entry<String, String> entry : carrier.entrySet()) {
      String lowerCaseKey = entry.getKey().toLowerCase()
      int classification = classifier.classify(lowerCaseKey)
      if (classification != IGNORE) {
        if (!consumer.accept(classification, lowerCaseKey, entry.getValue())) {
          return
        }
      }
    }
  }
}
