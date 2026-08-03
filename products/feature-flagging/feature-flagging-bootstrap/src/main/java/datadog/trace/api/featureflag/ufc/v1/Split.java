package datadog.trace.api.featureflag.ufc.v1;

import java.util.List;
import java.util.Map;

public class Split {
  public final List<Shard> shards;
  public final String variationKey;
  public final Map<String, String> extraLogging;
  // Nullable Integer (not primitive int): the serialId is absent in some UFC shapes. Populated by
  // Moshi reflective deserialization from the UFC "serialId" JSON field. Surfaced as
  // __dd_split_serial_id in eval metadata for APM span enrichment.
  public final Integer serialId;

  public Split(
      final List<Shard> shards,
      final String variationKey,
      final Map<String, String> extraLogging,
      final Integer serialId) {
    this.shards = shards;
    this.variationKey = variationKey;
    this.extraLogging = extraLogging;
    this.serialId = serialId;
  }
}
