package datadog.trace.instrumentation.aws.v2.sns;

import datadog.context.propagation.CarrierSetter;

public class TextMapInjectAdapter implements CarrierSetter<StringBuilder> {

  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(final StringBuilder builder, final String key, final String value) {
    builder.append('\"').append(key).append("\":\"").append(value).append("\",");
  }
}
