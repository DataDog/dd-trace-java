package datadog.trace.instrumentation.opentracing31;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import io.opentracing.propagation.TextMap;

class OTPropagation {

  static class TextMapInjectSetter implements AgentPropagation.Setter<TextMap> {
    static final TextMapInjectSetter INSTANCE = new TextMapInjectSetter();

    @Override
    public void set(final TextMap carrier, final String key, final String value) {
      carrier.put(key, value);
    }
  }
}
