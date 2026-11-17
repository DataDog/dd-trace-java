package com.datadog.openfeature.internal.delivery;

import com.datadog.openfeature.internal.RuntimeServices;
import com.datadog.openfeature.internal.bridge.v1.HealthMetrics;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Test helpers for the flag selection diagnostics, which are reported once per class loader. */
public final class FlagSelectionTestSupport {
  private FlagSelectionTestSupport() {}

  /** Forgets the diagnostics already reported, so a test sees its own first ones. */
  public static void resetReported() {
    FlagSelectionRequest.resetReportedForTest();
  }

  /** Records the health counters it receives, as {@code name|value|reason}. */
  public static final class RecordingHealthMetrics implements HealthMetrics {
    public final List<String> counts = new CopyOnWriteArrayList<>();

    @Override
    public void count(final String metric, final long value, final String reason) {
      this.counts.add(metric + "|" + value + "|" + reason);
    }

    public RuntimeServices services() {
      return new RuntimeServices(this);
    }
  }
}
