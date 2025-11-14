package datadog.trace.api.openfeature.config.ufc.v1;

import java.util.List;
import java.util.Map;

public class Split {
  public final List<Shard> shards;
  public final String variationKey;
  public final Map<String, String> extraLogging;

  public Split(
      final List<Shard> shards, final String variationKey, final Map<String, String> extraLogging) {
    this.shards = shards;
    this.variationKey = variationKey;
    this.extraLogging = extraLogging;
  }
}
