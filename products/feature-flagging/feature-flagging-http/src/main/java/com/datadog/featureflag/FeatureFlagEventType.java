package com.datadog.featureflag;

/** Defines event-specific transport behavior for Feature Flag delivery. */
enum FeatureFlagEventType {
  // Keep the established exposure transport behavior for compatibility.
  EXPOSURE("exposure", true),

  // Flag evaluation writers ignore successful response bodies, so gzip negotiation adds no value.
  FLAG_EVALUATION("flag evaluation", false);

  private final String logName;
  private final boolean responseCompressionEnabled;

  FeatureFlagEventType(final String logName, final boolean responseCompressionEnabled) {
    this.logName = logName;
    this.responseCompressionEnabled = responseCompressionEnabled;
  }

  String logName() {
    return logName;
  }

  boolean responseCompressionEnabled() {
    return responseCompressionEnabled;
  }
}
