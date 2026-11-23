package com.datadog.openfeature.internal.ufc;

import java.util.Collections;
import java.util.Map;

public final class ServerConfiguration {
  public final String createdAt;
  public final String format;

  /** PII consent; {@code null} must be read as the privacy-preserving {@code false}. */
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
