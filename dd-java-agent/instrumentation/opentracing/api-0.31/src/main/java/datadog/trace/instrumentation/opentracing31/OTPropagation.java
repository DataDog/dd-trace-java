package datadog.trace.instrumentation.opentracing31;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import io.opentracing.propagation.TextMap;
import java.util.HashMap;
import java.util.Map;

class OTPropagation {

  static class TextMapInjectSetter implements AgentPropagation.Setter<TextMap> {
    static final TextMapInjectSetter INSTANCE = new TextMapInjectSetter();

    @Override
    public void set(final TextMap carrier, final String key, final String value) {
      carrier.put(key, value);
    }
  }

  static class TextMapExtractGetter implements AgentPropagation.Getter<TextMap> {
    private final Map<String, String> extracted = new HashMap<>();

    TextMapExtractGetter(final TextMap carrier) {
      for (final Map.Entry<String, String> entry : carrier) {
        extracted.put(entry.getKey(), entry.getValue());
      }
    }

    @Override
    public Iterable<String> keys(final TextMap carrier) {
      return extracted.keySet();
    }

    @Override
    public String get(final TextMap carrier, final String key) {
      // This is the same as the one passed into the constructor
      // So using "extracted" is valid
      return extracted.get(key);
    }
  }
}
