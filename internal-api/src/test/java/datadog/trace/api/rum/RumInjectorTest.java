package datadog.trace.api.rum;

import static datadog.trace.api.rum.RumTelemetryCollector.NO_OP;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import datadog.trace.api.Config;
import datadog.trace.api.InstrumenterConfig;
import datadog.trace.test.util.DDJavaSpecification;
import org.junit.jupiter.api.Test;

class RumInjectorTest extends DDJavaSpecification {
  static final String UTF8 = "UTF-8";

  @Test
  void disabledInjector() {
    Config config = mock(Config.class);
    InstrumenterConfig instrumenterConfig = mock(InstrumenterConfig.class);

    // when
    when(instrumenterConfig.isRumEnabled()).thenReturn(false);
    RumInjector injector = new RumInjector(config, instrumenterConfig);

    // then
    assertFalse(injector.isEnabled());
    assertNull(injector.getMarkerBytes(UTF8));
    assertNull(injector.getSnippetBytes(UTF8));
  }

  @Test
  void invalidConfigInjector() {
    Config config = mock(Config.class);
    InstrumenterConfig instrumenterConfig = mock(InstrumenterConfig.class);

    // when
    when(instrumenterConfig.isRumEnabled()).thenReturn(true);
    when(config.getRumInjectorConfig()).thenReturn(null);
    RumInjector injector = new RumInjector(config, instrumenterConfig);

    // then
    assertFalse(injector.isEnabled());
    assertNull(injector.getMarkerBytes(UTF8));
    assertNull(injector.getSnippetBytes(UTF8));
    assertNull(injector.getSnippetChars());
    assertNull(injector.getMarkerChars());
  }

  @Test
  void enabledInjector() {
    Config config = mock(Config.class);
    InstrumenterConfig instrumenterConfig = mock(InstrumenterConfig.class);
    RumInjectorConfig injectorConfig = mock(RumInjectorConfig.class);

    // when
    when(instrumenterConfig.isRumEnabled()).thenReturn(true);
    when(config.getRumInjectorConfig()).thenReturn(injectorConfig);
    when(injectorConfig.getSnippet()).thenReturn("<script></script>");
    RumInjector injector = new RumInjector(config, instrumenterConfig);

    // then
    assertTrue(injector.isEnabled());
    assertNotNull(injector.getMarkerBytes(UTF8));
    assertNotNull(injector.getSnippetBytes(UTF8));
    assertNotNull(injector.getSnippetChars());
    assertNotNull(injector.getMarkerChars());
  }

  @Test
  void setTelemetryCollector() {
    RumTelemetryCollector telemetryCollector = mock(RumTelemetryCollector.class);
    try {
      // when
      RumInjector.setTelemetryCollector(telemetryCollector);

      // then
      assertSame(telemetryCollector, RumInjector.getTelemetryCollector());
    } finally {
      RumInjector.setTelemetryCollector(NO_OP);
    }
  }

  @Test
  void returnNoOpWhenTelemetryCollectorIsNotSet() {
    // when
    RumInjector.setTelemetryCollector(null);

    // then
    assertSame(NO_OP, RumInjector.getTelemetryCollector());
  }

  // enableTelemetry() checks that INSTANCE.isEnabled() before starting telemetry collection.
  // However, INSTANCE is a static final field created at class loading, so we test whatever the
  // actual RUM configuration is.
  @Test
  void enableTelemetry() {
    // when
    RumInjector.enableTelemetry();
    RumTelemetryCollector collector = RumInjector.getTelemetryCollector();
    boolean isRumEnabled = RumInjector.get().isEnabled();

    // then
    assertNotNull(collector);
    if (isRumEnabled) {
      assertInstanceOf(RumInjectorMetrics.class, collector);
      assertNotSame(NO_OP, collector);
    } else {
      assertSame(NO_OP, collector);
    }
  }

  @Test
  void shutdownTelemetry() {
    RumInjector.enableTelemetry();

    // when
    RumInjector.shutdownTelemetry();

    // then
    assertSame(NO_OP, RumInjector.getTelemetryCollector());
  }
}
