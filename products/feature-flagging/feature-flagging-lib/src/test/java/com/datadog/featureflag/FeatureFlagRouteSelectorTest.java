package com.datadog.featureflag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class FeatureFlagRouteSelectorTest {

  @Test
  void unavailableLoggingIsSharedAndOnlyRepeatsAfterRecovery() {
    final Logger logger = (Logger) LoggerFactory.getLogger(FeatureFlagRouteSelector.class);
    final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    appender.start();
    logger.addAppender(appender);
    try {
      final AtomicLong clock = new AtomicLong();
      final FeatureFlagRouteSelector selector = new FeatureFlagRouteSelector(clock::get, 10);
      selector.initialize(() -> null, false);
      selector.initialize(() -> null, false);
      for (int i = 1; i <= 3; i++) {
        clock.set(i * 10);
        assertTrue(selector.tryBeginLocalRecovery());
        selector.localRecoveryFinished(null);
      }
      assertEquals(1, appender.list.size());
      assertEquals(Level.WARN, appender.list.get(0).getLevel());
      assertTrue(appender.list.get(0).getFormattedMessage().contains("DD_API_KEY"));
      clock.set(40);
      assertTrue(selector.tryBeginLocalRecovery());
      selector.localRecoveryFinished("v4");
      assertEquals(2, appender.list.size());
      assertEquals(Level.INFO, appender.list.get(1).getLevel());
      selector.localFailure(selector.localRoute(), false);
      assertEquals(3, appender.list.size());
      assertEquals(Level.WARN, appender.list.get(2).getLevel());
    } finally {
      logger.detachAppender(appender);
      appender.stop();
    }
  }

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
