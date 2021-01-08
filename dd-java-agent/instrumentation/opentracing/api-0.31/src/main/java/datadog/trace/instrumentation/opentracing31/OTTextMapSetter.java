package datadog.trace.instrumentation.opentracing31;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import io.opentracing.propagation.TextMap;

class OTTextMapSetter implements AgentPropagation.Setter<TextMap> {
  static final OTTextMapSetter INSTANCE = new OTTextMapSetter();

  @Override
  public void set(final TextMap carrier, final String key, final String value) {
    carrier.put(key, value);
  }
}
