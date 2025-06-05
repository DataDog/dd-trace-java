package datadog.trace.instrumentation.aws.v2.eventbridge;

import datadog.context.propagation.CarrierSetter;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
public class TextMapInjectAdapter implements CarrierSetter<StringBuilder> {

  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(final StringBuilder builder, final String key, final String value) {
    builder.append('"').append(key).append("\":\"").append(value).append("\",");
  }
}
