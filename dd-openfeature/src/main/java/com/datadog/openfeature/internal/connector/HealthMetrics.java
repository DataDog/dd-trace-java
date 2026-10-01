package com.datadog.openfeature.internal.connector;

import javax.annotation.Nullable;

/** Records SDK health counters. */
@FunctionalInterface
public interface HealthMetrics {
  /** The sink discarding all counters. */
  HealthMetrics NOOP = (metric, value, reason) -> {};

  /**
   * Increments a counter.
   *
   * @param metric the metric name.
   * @param value the increment.
   * @param reason the optional {@code reason} tag value.
   */
  void count(String metric, long value, @Nullable String reason);
}
