package com.datadog.openfeature.internal.ufc;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

public final class Split {
  public final List<Shard> shards;
  public final String variationKey;
  public final Map<String, String> extraLogging;

  /** Absent in some UFC shapes. Surfaced as {@code __dd_split_serial_id} for span enrichment. */
  public final Integer serialId;

  /** Null when the UFC split has no features. */
  public final List<Feature> features;

  // What the evaluator derives from the features, built when the split is first served.
  private volatile Object preparedFeatures;

  public Split(
      final List<Shard> shards,
      final String variationKey,
      final Map<String, String> extraLogging,
      final Integer serialId) {
    this(shards, variationKey, extraLogging, serialId, null);
  }

  public Split(
      final List<Shard> shards,
      final String variationKey,
      final Map<String, String> extraLogging,
      final Integer serialId,
      final List<Feature> features) {
    this.shards = shards;
    this.variationKey = variationKey;
    this.extraLogging = extraLogging;
    this.serialId = serialId;
    this.features = features;
  }

  /**
   * Returns what {@code prepare} derives from the features, computed once for the split. Two
   * evaluations that serve the split first at the same time can both compute it; either result is
   * kept. Every caller passes the same function, so the cached result does not depend on which one
   * ran first.
   *
   * @param prepare derives the prepared form from {@link #features}, which can be {@code null}. It
   *     never returns {@code null}.
   * @return the prepared form.
   */
  @SuppressWarnings("unchecked")
  public <T> T preparedFeatures(final Function<List<Feature>, T> prepare) {
    Object prepared = preparedFeatures;
    if (prepared == null) {
      prepared = prepare.apply(features);
      preparedFeatures = prepared;
    }
    return (T) prepared;
  }
}
