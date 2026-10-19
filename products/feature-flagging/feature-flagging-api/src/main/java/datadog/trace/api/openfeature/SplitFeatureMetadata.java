package datadog.trace.api.openfeature;

import dev.openfeature.sdk.ImmutableMetadata;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads the split features that {@link DDEvaluator} copies into flag metadata. It depends only on
 * the OpenFeature API, so {@link ExposureHook} keeps working where the Datadog Java agent is
 * absent.
 */
final class SplitFeatureMetadata {

  private SplitFeatureMetadata() {}

  /** The features stored in the metadata under {@code prefix}, keyed without the prefix. */
  static Map<String, Object> featuresWithPrefix(
      final ImmutableMetadata metadata, final String prefix) {
    if (metadata == null) {
      return Collections.emptyMap();
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
    return features == null
        ? Collections.<String, Object>emptyMap()
        : Collections.unmodifiableMap(features);
  }
}
