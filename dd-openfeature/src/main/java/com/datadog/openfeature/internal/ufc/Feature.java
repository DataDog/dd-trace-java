package com.datadog.openfeature.internal.ufc;

import java.util.List;

/** Key-value data that the UFC attaches to a split, with where SDKs deliver it. */
public final class Feature {
  public final String key;

  /** A {@link String}, {@link Double} or {@link Boolean}; the parser drops any other value. */
  public final Object value;

  /**
   * Open strings such as {@code HOOK}, {@code EXPOSURE} or {@code EVALUATION}. Never empty: the
   * parser drops a feature without destinations.
   */
  public final List<String> destinations;

  public Feature(final String key, final Object value, final List<String> destinations) {
    this.key = key;
    this.value = value;
    this.destinations = destinations;
  }
}
