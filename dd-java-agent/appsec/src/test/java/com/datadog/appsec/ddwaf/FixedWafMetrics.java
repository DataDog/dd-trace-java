package com.datadog.appsec.ddwaf;

import com.datadog.ddwaf.WafMetrics;

/** WAF metrics reporting fixed durations, whatever the WAF actually measured. */
final class FixedWafMetrics extends WafMetrics {
  private final long totalDdwafRunTimeNs;
  private final long totalRunTimeNs;

  FixedWafMetrics(long totalDdwafRunTimeNs, long totalRunTimeNs) {
    this.totalDdwafRunTimeNs = totalDdwafRunTimeNs;
    this.totalRunTimeNs = totalRunTimeNs;
  }

  @Override
  public long getTotalDdwafRunTimeNs() {
    return totalDdwafRunTimeNs;
  }

  @Override
  public long getTotalRunTimeNs() {
    return totalRunTimeNs;
  }

  @Override
  public long getTruncatedStringTooLongCount() {
    return 0;
  }

  @Override
  public long getTruncatedListMapTooLargeCount() {
    return 0;
  }

  @Override
  public long getTruncatedObjectTooDeepCount() {
    return 0;
  }
}
