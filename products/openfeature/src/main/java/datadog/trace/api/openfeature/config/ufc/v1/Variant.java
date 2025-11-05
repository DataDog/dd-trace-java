package datadog.trace.api.openfeature.config.ufc.v1;

public class Variant {
  public final String key;
  public final Object value;

  public Variant(final String key, final Object value) {
    this.key = key;
    this.value = value;
  }
}
