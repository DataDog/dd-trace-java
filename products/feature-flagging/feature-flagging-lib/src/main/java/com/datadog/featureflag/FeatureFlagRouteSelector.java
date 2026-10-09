package com.datadog.featureflag;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Process-wide route state shared by all Feature Flagging event writers. */
final class FeatureFlagRouteSelector {

  private static final Logger LOGGER = LoggerFactory.getLogger(FeatureFlagRouteSelector.class);

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
  private LocalRoute localRoute;
  private boolean recoveryInProgress;

  /** A validated endpoint and its identity, replaced on every successful recovery. */
  static final class LocalRoute {
    final String endpoint;

    private LocalRoute(final String endpoint) {
      this.endpoint = endpoint;
    }
  }

  FeatureFlagRouteSelector() {
    this(System::nanoTime, DEFAULT_RECOVERY_INTERVAL_NANOS);
  }

  FeatureFlagRouteSelector(final LongSupplier nanoTime, final long recoveryIntervalNanos) {
    this.nanoTime = nanoTime;
    this.recoveryIntervalNanos = recoveryIntervalNanos;
  }

  synchronized Route initialize(
      final Supplier<String> discoverEndpoint, final boolean directAvailable) {
    if (route == Route.UNINITIALIZED) {
      // Startup happens once across both writers. Later unavailable probes run outside this lock.
      final String endpoint = discoverEndpoint.get();
      if (endpoint != null) {
        localRoute = new LocalRoute(endpoint);
        route = Route.LOCAL;
      } else if (directAvailable) {
        route = Route.DIRECT;
      } else {
        route = Route.UNAVAILABLE;
        scheduleLocalDiscovery();
        logUnavailable();
      }
    }
    return route;
  }

  Route current() {
    return route;
  }

  synchronized @Nullable LocalRoute localRoute() {
    return route == Route.LOCAL ? localRoute : null;
  }

  synchronized Route localFailure(final LocalRoute failedRoute, final boolean directAvailable) {
    // An in-flight send on an old endpoint cannot invalidate a newly recovered route.
    if (route == Route.LOCAL && localRoute == failedRoute) {
      if (directAvailable) {
        route = Route.DIRECT;
      } else {
        route = Route.UNAVAILABLE;
        scheduleLocalDiscovery();
        logUnavailable();
      }
    }
    return route;
  }

  synchronized boolean tryBeginLocalRecovery() {
    if (route != Route.UNAVAILABLE
        || recoveryInProgress
        || nanoTime.getAsLong() - nextLocalDiscoveryNanos < 0) {
      return false;
    }
    recoveryInProgress = true;
    return true;
  }

  synchronized void localRecoveryFinished(@Nullable final String endpoint) {
    recoveryInProgress = false;
    if (route == Route.UNAVAILABLE) {
      if (endpoint != null) {
        localRoute = new LocalRoute(endpoint);
        route = Route.LOCAL;
        LOGGER.info("Feature Flagging event delivery recovered through the local EVP proxy");
      } else {
        scheduleLocalDiscovery();
      }
    }
  }

  private void scheduleLocalDiscovery() {
    nextLocalDiscoveryNanos = nanoTime.getAsLong() + recoveryIntervalNanos;
  }

  private static void logUnavailable() {
    LOGGER.warn(
        "Feature Flagging events are being dropped because no local EVP proxy or direct intake"
            + " route is available. Make the Agent reachable, or configure DD_API_KEY and a valid DD_SITE.");
  }
}
