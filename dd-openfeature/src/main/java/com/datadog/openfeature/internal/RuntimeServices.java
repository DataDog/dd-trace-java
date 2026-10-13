package com.datadog.openfeature.internal;

import com.datadog.openfeature.internal.connector.HealthMetrics;
import javax.annotation.Nullable;

/** Thread creation and health metrics shared by the runtime components. */
public final class RuntimeServices {
  private final HealthMetrics healthMetrics;

  public RuntimeServices(final HealthMetrics healthMetrics) {
    this.healthMetrics = healthMetrics;
  }

  /**
   * Creates a daemon thread.
   *
   * @param role the thread role, used to name the thread.
   * @param task the thread task.
   * @return the created thread, not started yet.
   */
  public Thread newThread(final String role, final Runnable task) {
    final Thread thread = new Thread(task, "dd-openfeature-" + role);
    thread.setDaemon(true);
    thread.setContextClassLoader(null);
    return thread;
  }

  /**
   * Increments a health counter, skipping non-positive values.
   *
   * @param name the metric name.
   * @param value the increment.
   * @param reason the optional {@code reason} tag value.
   */
  public void countMetric(final String name, final long value, @Nullable final String reason) {
    if (value > 0) {
      this.healthMetrics.count(name, value, reason);
    }
  }
}
