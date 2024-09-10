package datadog.trace.instrumentation.aws.v2.eventbridge;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

public class TextMapInjectAdapter implements AgentPropagation.Setter<StringBuilder> {

  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(final StringBuilder builder, final String key, final String value) {
    builder.append("\"").append(key).append("\":\"").append(value).append("\",");
  }
}
