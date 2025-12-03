package datadog.trace.api.featureflag.ufc.v1;

public class Variant {
  public final String key;
  public final Object value;

  public Variant(final String key, final Object value) {
    this.key = key;
    this.value = value;
  }
}
