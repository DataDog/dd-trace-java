package datadog.trace.api.featureflag.ufc.v1;

/** Key-value data that the UFC attaches to a split for exposure hooks. */
public class Feature {
  public final String key;
  // A String, Double or Boolean; the parser drops features with any other value.
  public final Object value;

  public Feature(final String key, final Object value) {
    this.key = key;
    this.value = value;
  }
}
