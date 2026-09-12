package com.datadog.featureflag;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/** Process-wide route state shared by all Feature Flagging event writers. */
final class FeatureFlagRouteSelector {

  static final long DEFAULT_RECOVERY_INTERVAL_NANOS = TimeUnit.MINUTES.toNanos(1);

  enum Route {
    UNINITIALIZED,
    LOCAL,
    DIRECT,
    UNAVAILABLE
  }

  private final LongSupplier nanoTime;
  private final long recoveryIntervalNanos;
  private volatile Route route = Route.UNINITIALIZED;
  private volatile long nextLocalDiscoveryNanos;

  FeatureFlagRouteSelector() {
    this(System::nanoTime, DEFAULT_RECOVERY_INTERVAL_NANOS);
  }

  FeatureFlagRouteSelector(final LongSupplier nanoTime, final long recoveryIntervalNanos) {
    this.nanoTime = nanoTime;
    this.recoveryIntervalNanos = recoveryIntervalNanos;
  }

  synchronized Route initialize(final boolean localAvailable, final boolean directAvailable) {
    if (route == Route.UNINITIALIZED || route == Route.UNAVAILABLE) {
      if (localAvailable) {
        route = Route.LOCAL;
      } else if (directAvailable) {
        route = Route.DIRECT;
      } else {
        route = Route.UNAVAILABLE;
        scheduleLocalDiscovery();
      }
    }
    return route;
  }

  Route current() {
    return route;
  }

  synchronized Route localFailure(final boolean directAvailable) {
    if (route == Route.LOCAL) {
      if (directAvailable) {
        route = Route.DIRECT;
      } else {
        route = Route.UNAVAILABLE;
        scheduleLocalDiscovery();
      }
    }
    return route;
  }

  synchronized boolean tryBeginLocalRecovery() {
    if (route != Route.UNAVAILABLE || nanoTime.getAsLong() - nextLocalDiscoveryNanos < 0) {
      return false;
    }
    scheduleLocalDiscovery();
    return true;
  }

  synchronized void localRecovered() {
    if (route == Route.UNAVAILABLE) {
      route = Route.LOCAL;
    }
  }

  private void scheduleLocalDiscovery() {
    nextLocalDiscoveryNanos = nanoTime.getAsLong() + recoveryIntervalNanos;
  }
}
