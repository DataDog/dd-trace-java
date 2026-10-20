package com.datadog.openfeature;

import static java.util.Collections.emptyMap;
import static java.util.Collections.unmodifiableMap;

import dev.openfeature.sdk.ImmutableMetadata;
import java.util.HashMap;
import java.util.Map;

/** Reads the split features that the evaluator copies into the flag metadata. */
final class SplitFeatureMetadata {

  private SplitFeatureMetadata() {}

  /**
   * Reads the features stored in the metadata under a prefix.
   *
   * @param metadata the flag metadata, or {@code null} if absent.
   * @param prefix the destination prefix of the features.
   * @return the unmodifiable features keyed without the prefix, empty if none.
   */
  static Map<String, Object> featuresWithPrefix(
      final ImmutableMetadata metadata, final String prefix) {
    if (metadata == null) {
      return emptyMap();
    }
    Map<String, Object> features = null;
    for (final Map.Entry<String, Object> entry : metadata.asUnmodifiableMap().entrySet()) {
      if (entry.getKey().startsWith(prefix)) {
        if (features == null) {
          features = new HashMap<>();
        }
        features.put(entry.getKey().substring(prefix.length()), entry.getValue());
      }
    }
    return features == null ? emptyMap() : unmodifiableMap(features);
  }
}
