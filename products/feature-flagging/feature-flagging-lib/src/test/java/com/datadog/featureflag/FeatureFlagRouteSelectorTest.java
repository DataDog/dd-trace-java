package com.datadog.featureflag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class FeatureFlagRouteSelectorTest {

  @Test
  void directRouteIsTerminalAcrossInitializationAndRecoverySignals() {
    final FeatureFlagRouteSelector routeSelector = new FeatureFlagRouteSelector();

    assertEquals(FeatureFlagRouteSelector.Route.DIRECT, routeSelector.initialize(() -> null, true));
    assertEquals(
        FeatureFlagRouteSelector.Route.DIRECT, routeSelector.initialize(() -> "v4", false));
    assertFalse(routeSelector.tryBeginLocalRecovery());

    routeSelector.localRecoveryFinished("v4");

    assertEquals(FeatureFlagRouteSelector.Route.DIRECT, routeSelector.current());
  }

  @Test
  void anotherWriterCannotBypassTheRecoveryCooldown() {
    final AtomicLong clock = new AtomicLong();
    final FeatureFlagRouteSelector routeSelector = new FeatureFlagRouteSelector(clock::get, 10);

    assertEquals(
        FeatureFlagRouteSelector.Route.UNAVAILABLE, routeSelector.initialize(() -> null, false));
    assertEquals(
        FeatureFlagRouteSelector.Route.UNAVAILABLE,
        routeSelector.initialize(
            () -> {
              throw new AssertionError("duplicate discovery");
            },
            false));
    assertFalse(routeSelector.tryBeginLocalRecovery());
    clock.set(10);
    assertTrue(routeSelector.tryBeginLocalRecovery());
    clock.set(100);
    assertFalse(routeSelector.tryBeginLocalRecovery());
    routeSelector.localRecoveryFinished(null);
    assertFalse(routeSelector.tryBeginLocalRecovery());
    clock.set(110);
    assertTrue(routeSelector.tryBeginLocalRecovery());
  }

  @Test
  void staleFailureCannotInvalidateANewGenerationOfTheSameEndpoint() {
    final AtomicLong clock = new AtomicLong();
    final FeatureFlagRouteSelector selector = new FeatureFlagRouteSelector(clock::get, 10);
    selector.initialize(() -> "v4", false);
    final FeatureFlagRouteSelector.LocalRoute oldRoute = selector.localRoute();
    selector.localFailure(oldRoute, false);
    clock.set(10);
    assertTrue(selector.tryBeginLocalRecovery());
    selector.localRecoveryFinished("v4");
    assertNotSame(oldRoute, selector.localRoute());
    selector.localFailure(oldRoute, true);
    assertEquals(FeatureFlagRouteSelector.Route.LOCAL, selector.current());
  }
}
