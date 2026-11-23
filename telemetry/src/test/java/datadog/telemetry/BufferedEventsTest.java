package datadog.telemetry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.ConfigOrigin;
import datadog.trace.api.ConfigSetting;
import datadog.trace.api.telemetry.Endpoint;
import org.junit.jupiter.api.Test;

class BufferedEventsTest {

  @Test
  void emptyEvents() {
    BufferedEvents events = new BufferedEvents();

    assertTrue(events.isEmpty());
    assertFalse(events.hasConfigChangeEvent());
    assertFalse(events.hasDependencyEvent());
    assertFalse(events.hasDistributionSeriesEvent());
    assertFalse(events.hasIntegrationEvent());
    assertFalse(events.hasLogMessageEvent());
    assertFalse(events.hasMetricEvent());
    assertFalse(events.hasEndpoint());
  }

  @Test
  void returnAddedEvents() {
    BufferedEvents events = new BufferedEvents();
    ConfigSetting configSetting = ConfigSetting.of("key", "value", ConfigOrigin.DEFAULT);
    Dependency dependency = new Dependency("name", "version", "source", "hash");
    DistributionSeries series = new DistributionSeries();
    Integration integration = new Integration("integration-name", true);
    LogMessage logMessage = new LogMessage();
    Metric metric = new Metric();
    Endpoint endpoint = new Endpoint();

    // when
    events.addConfigChangeEvent(configSetting);

    // then
    assertFalse(events.isEmpty());
    assertTrue(events.hasConfigChangeEvent());
    assertEquals(configSetting, events.nextConfigChangeEvent());
    assertFalse(events.hasConfigChangeEvent());
    assertTrue(events.isEmpty());

    // when
    events.addDependencyEvent(dependency);

    // then
    assertFalse(events.isEmpty());
    assertTrue(events.hasDependencyEvent());
    assertEquals(dependency, events.nextDependencyEvent());
    assertFalse(events.hasDependencyEvent());
    assertTrue(events.isEmpty());

    // when
    events.addDistributionSeriesEvent(series);

    // then
    assertFalse(events.isEmpty());
    assertTrue(events.hasDistributionSeriesEvent());
    assertEquals(series, events.nextDistributionSeriesEvent());
    assertFalse(events.hasDistributionSeriesEvent());
    assertTrue(events.isEmpty());

    // when
    events.addIntegrationEvent(integration);

    // then
    assertFalse(events.isEmpty());
    assertTrue(events.hasIntegrationEvent());
    assertEquals(integration, events.nextIntegrationEvent());
    assertFalse(events.hasIntegrationEvent());
    assertTrue(events.isEmpty());

    // when
    events.addLogMessageEvent(logMessage);

    // then
    assertFalse(events.isEmpty());
    assertTrue(events.hasLogMessageEvent());
    assertEquals(logMessage, events.nextLogMessageEvent());
    assertFalse(events.hasLogMessageEvent());
    assertTrue(events.isEmpty());

    // when
    events.addMetricEvent(metric);

    // then
    assertFalse(events.isEmpty());
    assertTrue(events.hasMetricEvent());
    assertEquals(metric, events.nextMetricEvent());
    assertFalse(events.hasMetricEvent());
    assertTrue(events.isEmpty());

    // when
    events.addEndpointEvent(endpoint);

    // then
    assertFalse(events.isEmpty());
    assertTrue(events.hasEndpoint());
    assertEquals(endpoint, events.nextEndpoint());
    assertFalse(events.hasEndpoint());
    assertTrue(events.isEmpty());
  }

  @Test
  void noopSink() {
    EventSink sink = EventSink.NOOP;

    sink.addMetricEvent(null);
    sink.addLogMessageEvent(null);
    sink.addIntegrationEvent(null);
    sink.addDistributionSeriesEvent(null);
    sink.addDependencyEvent(null);
    sink.addConfigChangeEvent(null);
    sink.addEndpointEvent(null);
  }
}
