package com.datadog.openfeature.internal.evaluation;

import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EVALUATION_FEATURES_HANDLE;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EVALUATION_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.EXPOSURE_FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.FEATURE_PREFIX;
import static com.datadog.openfeature.internal.evaluation.MetadataKeys.HAS_EVALUATION_FEATURES;

import com.datadog.openfeature.internal.ufc.Feature;
import dev.openfeature.sdk.ImmutableMetadata;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The metadata entries of a split's features, resolved when the split is first served and then
 * cached on it: the first feature of each key wins, each destination gets its prefixed key, and
 * unknown destinations are dropped. Later evaluations only copy the entries.
 *
 * <p>The features routed to flag evaluations are also built once into a map, registered with {@link
 * EvaluationFeatureHandles}, and passed to the flag evaluation hook by handle.
 */
final class SplitFeatures {
  private static final Logger LOGGER = LoggerFactory.getLogger(SplitFeatures.class);
  static final String FEATURE_DESTINATION_HOOK = "HOOK";
  static final String FEATURE_DESTINATION_EXPOSURE = "EXPOSURE";
  static final String FEATURE_DESTINATION_EVALUATION = "EVALUATION";
  // A newer UFC can name destinations this SDK does not know; warn once per name, not per
  // evaluation.
  static final int MAX_WARNED_FEATURE_DESTINATIONS = 64;
  static final Set<String> WARNED_FEATURE_DESTINATIONS = ConcurrentHashMap.newKeySet();

  /** The prepared features of a split without metadata entries, shared by all such splits. */
  static final SplitFeatures NONE = new SplitFeatures(new String[0], new Object[0], false, 0L);

  private final String[] keys;

  /** A {@link String}, {@link Boolean} or {@link Double}. */
  private final Object[] values;

  private final boolean hasEvaluationFeatures;

  /**
   * The {@link EvaluationFeatureHandles} handle of the evaluation features, {@code null} if none.
   * It is boxed once here, so writing it to each evaluation's metadata does not allocate.
   */
  private final Long evaluationFeaturesHandle;

  private SplitFeatures(
      final String[] keys,
      final Object[] values,
      final boolean hasEvaluationFeatures,
      final long evaluationFeaturesHandle) {
    this.keys = keys;
    this.values = values;
    this.hasEvaluationFeatures = hasEvaluationFeatures;
    this.evaluationFeaturesHandle =
        evaluationFeaturesHandle == 0L ? null : evaluationFeaturesHandle;
  }

  /**
   * Resolves the metadata entries of features.
   *
   * @param features the split's features, or {@code null}.
   * @return the prepared entries, {@link #NONE} if there are none.
   */
  static SplitFeatures prepare(final List<Feature> features) {
    if (features == null) {
      return NONE;
    }
    final List<String> keys = new ArrayList<>();
    final List<Object> values = new ArrayList<>();
    final Map<String, Object> evaluation = new HashMap<>();
    final Set<String> seenKeys = new HashSet<>();
    boolean evaluationFeatures = false;
    for (final Feature feature : features) {
      if (feature == null || feature.key == null || feature.destinations == null) {
        continue;
      }
      // The first feature with a key wins, for every destination.
      if (!seenKeys.add(feature.key)) {
        continue;
      }
      final Object value = metadataValue(feature.value);
      for (final String destination : feature.destinations) {
        final String prefix = metadataPrefix(destination);
        if (prefix != null) {
          if (value != null) {
            keys.add(prefix + feature.key);
            values.add(value);
            if (EVALUATION_FEATURE_PREFIX.equals(prefix)) {
              evaluation.put(feature.key, value);
            }
          }
          evaluationFeatures |= EVALUATION_FEATURE_PREFIX.equals(prefix);
        } else if (WARNED_FEATURE_DESTINATIONS.size() < MAX_WARNED_FEATURE_DESTINATIONS
            && WARNED_FEATURE_DESTINATIONS.add(destination)) {
          LOGGER.warn("Ignoring unknown feature destination {}", destination);
        }
      }
    }
    if (keys.isEmpty() && !evaluationFeatures) {
      return NONE;
    }
    // Matches what the hook would read back from the metadata entries.
    final long handle =
        evaluation.isEmpty()
            ? 0L
            : EvaluationFeatureHandles.register(EvaluationFeatureHandles.prepared(evaluation));
    return new SplitFeatures(
        keys.toArray(new String[0]), values.toArray(new Object[0]), evaluationFeatures, handle);
  }

  /** Adds the entries, and the evaluation-features marker when there are any, to metadata. */
  void addTo(final ImmutableMetadata.ImmutableMetadataBuilder metadataBuilder) {
    for (int i = 0; i < keys.length; i++) {
      final Object value = values[i];
      if (value instanceof String) {
        metadataBuilder.addString(keys[i], (String) value);
      } else if (value instanceof Boolean) {
        metadataBuilder.addBoolean(keys[i], (Boolean) value);
      } else {
        metadataBuilder.addDouble(keys[i], (Double) value);
      }
    }
    if (hasEvaluationFeatures) {
      metadataBuilder.addBoolean(HAS_EVALUATION_FEATURES, true);
    }
    if (evaluationFeaturesHandle != null) {
      metadataBuilder.addLong(EVALUATION_FEATURES_HANDLE, evaluationFeaturesHandle);
    }
  }

  long evaluationFeaturesHandle() {
    return evaluationFeaturesHandle == null ? 0L : evaluationFeaturesHandle;
  }

  /** The number of metadata entries, without the evaluation-features marker. */
  int size() {
    return keys.length;
  }

  private static Object metadataValue(final Object value) {
    if (value instanceof String || value instanceof Boolean) {
      return value;
    }
    if (value instanceof Number) {
      return ((Number) value).doubleValue();
    }
    return null;
  }

  private static String metadataPrefix(final String destination) {
    if (FEATURE_DESTINATION_HOOK.equals(destination)) {
      return FEATURE_PREFIX;
    }
    if (FEATURE_DESTINATION_EXPOSURE.equals(destination)) {
      return EXPOSURE_FEATURE_PREFIX;
    }
    if (FEATURE_DESTINATION_EVALUATION.equals(destination)) {
      return EVALUATION_FEATURE_PREFIX;
    }
    return null;
  }
}
