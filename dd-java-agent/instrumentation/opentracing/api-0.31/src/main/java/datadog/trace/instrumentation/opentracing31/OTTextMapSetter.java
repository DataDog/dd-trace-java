package datadog.trace.instrumentation.opentracing31;

import datadog.context.propagation.CarrierSetter;
import io.opentracing.propagation.TextMap;
import javax.annotation.ParametersAreNonnullByDefault;

@ParametersAreNonnullByDefault
class OTTextMapSetter implements CarrierSetter<TextMap> {
  static final OTTextMapSetter INSTANCE = new OTTextMapSetter();

  @Override
  public void set(final TextMap carrier, final String key, final String value) {
    carrier.put(key, value);
  }
}
