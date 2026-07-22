package datadog.trace.api.featureflag.ufc.v1;

import java.util.Map;

public class ServerConfiguration {
  public final String createdAt;
  public final String format;
  public final boolean observeFullEvaluationData;
  public final Environment environment;
  public final Map<String, Flag> flags;

  public ServerConfiguration(
      final String createdAt,
      final String format,
      final boolean observeFullEvaluationData,
      final Environment environment,
      final Map<String, Flag> flags) {
    this.createdAt = createdAt;
    this.format = format;
    this.observeFullEvaluationData = observeFullEvaluationData;
    this.environment = environment;
    this.flags = flags;
  }
}
