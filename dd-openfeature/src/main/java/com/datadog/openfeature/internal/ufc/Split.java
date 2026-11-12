package com.datadog.openfeature.internal.ufc;

import java.util.List;
import java.util.Map;

public final class Split {
  public final List<Shard> shards;
  public final String variationKey;
  public final Map<String, String> extraLogging;

  /** Absent in some UFC shapes. Surfaced as {@code __dd_split_serial_id} for span enrichment. */
  public final Integer serialId;

  /** Null when the UFC split has no features. */
  public final List<Feature> features;

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
}
