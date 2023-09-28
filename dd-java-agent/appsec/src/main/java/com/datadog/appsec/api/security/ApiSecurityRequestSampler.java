package com.datadog.appsec.api.security;

import datadog.trace.api.Config;
import java.util.concurrent.atomic.AtomicLong;

public class ApiSecurityRequestSampler {

  private final int sampling;
  private final AtomicLong cumulativeCounter = new AtomicLong();

  public ApiSecurityRequestSampler(final Config config) {
    sampling = computeSamplingParameter(config.getApiSecurityRequestSampleRate());
  }

  public boolean sampleRequest() {
    long prevValue = cumulativeCounter.getAndAdd(sampling);
    long newValue = prevValue + sampling;
    if (newValue / 100 == prevValue / 100 + 1) {
      // Sample request
      return true;
    }
    // Skipped by sampling
    return false;
  }

  static int computeSamplingParameter(final float pct) {
    if (pct >= 1) {
      return 100;
    }
    if (pct < 0) {
      // We don't support disabling Api Security by setting it, so we set it to 100%.
      // TODO: We probably want a warning here.
      return 100;
    }
    return (int) (pct * 100);
  }
}
