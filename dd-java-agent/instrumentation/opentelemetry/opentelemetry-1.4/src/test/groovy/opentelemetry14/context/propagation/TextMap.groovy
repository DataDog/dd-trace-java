package opentelemetry14.context.propagation

import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter

import javax.annotation.Nullable

class TextMap implements TextMapGetter<Map<String, String>>, TextMapSetter<Map<String, String>> {
  static final INSTANCE = new TextMap()

  @Override
  Iterable<String> keys(Map<String, String> carrier) {
    return carrier.keySet()
  }

  @Override
  String get(@Nullable Map<String, String> carrier, String key) {
    return carrier.get(key)
  }

  @Override
  void set(@Nullable Map<String, String> carrier, String key, String value) {
    carrier.put(key, value)
  }
}
