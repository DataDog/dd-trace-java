package datadog.trace.api.featureflag.ufc.v1;

import java.util.Collections;
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

  // Flags that could not be parsed or validated. The key is the flag key; the value is the error
  // type (e.g. "invalid_semver_comparand"). Set during configuration preprocessing, not from JSON.
  public transient Map<String, String> invalidFlags = Collections.emptyMap();

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
