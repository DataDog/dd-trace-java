package datadog.trace.instrumentation.opentracing32;

import datadog.context.propagation.CarrierSetter;
import io.opentracing.propagation.TextMapInject;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
class OTTextMapInjectSetter implements CarrierSetter<TextMapInject> {
  static final OTTextMapInjectSetter INSTANCE = new OTTextMapInjectSetter();

  @Override
  public void set(final TextMapInject carrier, final String key, final String value) {
    carrier.put(key, value);
  }
}
