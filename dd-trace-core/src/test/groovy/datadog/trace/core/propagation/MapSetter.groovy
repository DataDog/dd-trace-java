package datadog.trace.core.propagation

import datadog.context.propagation.CarrierSetter

import javax.annotation.ParametersAreNonnullByDefault

@ParametersAreNonnullByDefault
class MapSetter implements CarrierSetter<Map<String, String>> {
  static final INSTANCE = new MapSetter()

  @Override
  void set(Map<String, String> carrier, String key, String value) {
    carrier.put(key, value)
  }
}
