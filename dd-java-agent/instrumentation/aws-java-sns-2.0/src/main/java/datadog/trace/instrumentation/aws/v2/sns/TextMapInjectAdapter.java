package datadog.trace.instrumentation.aws.v2.sns;

import datadog.trace.bootstrap.instrumentation.api.AgentPropagation;

public class TextMapInjectAdapter implements AgentPropagation.Setter<StringBuilder> {

  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(final StringBuilder builder, final String key, final String value) {
    System.out.println("Setting\n");
    builder.append("\"").append(key).append("\":\"").append(value).append("\",");
  }
}
