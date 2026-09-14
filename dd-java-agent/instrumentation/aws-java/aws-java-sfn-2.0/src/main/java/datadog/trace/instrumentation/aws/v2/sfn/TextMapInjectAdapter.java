package datadog.trace.instrumentation.aws.v2.sfn;

import datadog.context.propagation.CarrierSetter;
import datadog.json.JsonWriter;

public class TextMapInjectAdapter implements CarrierSetter<JsonWriter> {

  public static final TextMapInjectAdapter SETTER = new TextMapInjectAdapter();

  @Override
  public void set(final JsonWriter writer, final String key, final String value) {
    writer.name(key).value(value);
  }
}
