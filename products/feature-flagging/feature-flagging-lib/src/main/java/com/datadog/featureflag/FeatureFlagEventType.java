package com.datadog.featureflag;

/** Defines event-specific transport behavior for Feature Flag delivery. */
enum FeatureFlagEventType {
  // Keep the established exposure transport behavior for compatibility.
  EXPOSURE(true),

  // Flag evaluation writers ignore successful response bodies, so gzip negotiation adds no value.
  FLAG_EVALUATION(false);

  private final boolean responseCompressionEnabled;

  FeatureFlagEventType(final boolean responseCompressionEnabled) {
    this.responseCompressionEnabled = responseCompressionEnabled;
  }

  boolean responseCompressionEnabled() {
    return responseCompressionEnabled;
  }
}
