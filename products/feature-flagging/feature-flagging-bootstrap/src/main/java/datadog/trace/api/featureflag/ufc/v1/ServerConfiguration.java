package datadog.trace.api.featureflag.ufc.v1;

import java.util.Map;

public class ServerConfiguration {
  public final String createdAt;
  public final String format;
  // Boxed on purpose. Moshi's reflective adapter for a primitive boolean field aborts the whole
  // UFC parse when the JSON value is null or not a boolean; with a Boolean field it tolerates
  // null (and other malformed values are still caught locally) so a malformed consent field
  // doesn't strand a fresh pod on PROVIDER_NOT_READY. Read sites must use
  // Boolean.TRUE.equals(...) so null falls to the privacy-preserving default.
  public final Boolean observeFullEvaluationData;
  public final Environment environment;
  public final Map<String, Flag> flags;

  public ServerConfiguration(
      final String createdAt,
      final String format,
      final Boolean observeFullEvaluationData,
      final Environment environment,
      final Map<String, Flag> flags) {
    this.createdAt = createdAt;
    this.format = format;
    this.observeFullEvaluationData = observeFullEvaluationData;
    this.environment = environment;
    this.flags = flags;
  }
}
