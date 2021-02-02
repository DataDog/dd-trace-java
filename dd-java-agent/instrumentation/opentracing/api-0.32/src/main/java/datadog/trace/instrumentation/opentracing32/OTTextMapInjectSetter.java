package datadog.trace.instrumentation.opentracing32;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;
import io.opentracing.propagation.TextMapInject;

class OTTextMapInjectSetter implements AgentPropagation.Setter<TextMapInject> {
  static final OTTextMapInjectSetter INSTANCE = new OTTextMapInjectSetter();

  @Override
  public void set(final TextMapInject carrier, final String key, final String value) {
    carrier.put(key, value);
  }
}
