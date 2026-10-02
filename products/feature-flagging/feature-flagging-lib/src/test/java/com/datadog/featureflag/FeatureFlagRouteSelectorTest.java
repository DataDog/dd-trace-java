package com.datadog.featureflag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FeatureFlagRouteSelectorTest {

  @Test
  void directRouteIsTerminalAcrossInitializationAndRecoverySignals() {
    final FeatureFlagRouteSelector routeSelector = new FeatureFlagRouteSelector();

    assertEquals(FeatureFlagRouteSelector.Route.DIRECT, routeSelector.initialize(false, true));
    assertEquals(FeatureFlagRouteSelector.Route.DIRECT, routeSelector.initialize(true, false));
    assertFalse(routeSelector.tryBeginLocalRecovery());

    routeSelector.localRecovered();

    assertEquals(FeatureFlagRouteSelector.Route.DIRECT, routeSelector.current());
  }

  @Test
  void unavailableRouteCanBeReinitializedWithLocal() {
    final AtomicLong clock = new AtomicLong();
    final FeatureFlagRouteSelector routeSelector = new FeatureFlagRouteSelector(clock::get, 10);

    assertEquals(
        FeatureFlagRouteSelector.Route.UNAVAILABLE, routeSelector.initialize(false, false));
    assertEquals(FeatureFlagRouteSelector.Route.LOCAL, routeSelector.initialize(true, false));
  }
}
