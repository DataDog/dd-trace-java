package datadog.trace.api.featureflag.ufc.v1;

import java.util.List;

/** Key-value data that the UFC attaches to a split, with where SDKs deliver it. */
public class Feature {
  public final String key;
  // A String, Double or Boolean; the parser drops features with any other value.
  public final Object value;
  // Open strings such as HOOK, EXPOSURE or EVALUATION. Never empty: the parser drops a feature
  // without destinations.
  public final List<String> destinations;

  public Feature(final String key, final Object value, final List<String> destinations) {
    this.key = key;
    this.value = value;
    this.destinations = destinations;
  }
}
