package datadog.trace.instrumentation.opentracing32;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import io.opentracing.propagation.TextMapExtract;
import io.opentracing.propagation.TextMapInject;
import java.util.HashMap;
import java.util.Map;

class OTPropagation {

  static class TextMapInjectSetter implements AgentPropagation.Setter<TextMapInject> {
    static final TextMapInjectSetter INSTANCE = new TextMapInjectSetter();

    @Override
    public void set(final TextMapInject carrier, final String key, final String value) {
      carrier.put(key, value);
    }
  }

  static class TextMapExtractGetter implements AgentPropagation.Getter<TextMapExtract> {
    private final Map<String, String> extracted = new HashMap<>();

    TextMapExtractGetter(final TextMapExtract carrier) {
      for (final Map.Entry<String, String> entry : carrier) {
        extracted.put(entry.getKey(), entry.getValue());
      }
    }

    @Override
    public Iterable<String> keys(final TextMapExtract carrier) {
      return extracted.keySet();
    }

    @Override
    public String get(final TextMapExtract carrier, final String key) {
      // This is the same as the one passed into the constructor
      // So using "extracted" is valid
      return extracted.get(key);
    }
  }
}
