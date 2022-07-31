package com.datadog.profiling.context;

import java.util.concurrent.TimeUnit;

final class ManualNanoTimeSource implements ExpirationTracker.NanoTimeSource {
  long ticks = 0;
  @Override
  public long getNanos() {
    return ticks;
  }

  void proceed(long time, TimeUnit timeUnit) {
    ticks += timeUnit.toNanos(time);
  }
}
