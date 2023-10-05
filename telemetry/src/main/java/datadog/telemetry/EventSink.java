package datadog.telemetry;

import datadog.telemetry.api.ConfigChange;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.dependency.Dependency;

/**
 * A unified interface for telemetry event sink. It is used to buffer events polled from the queues
 * to reattempt next sending attempt. A NOOP implementation is used to discard event on the next
 * failing attempt.
 */
interface EventSink {
  void addConfigChangeEvent(ConfigChange event);

  void addIntegrationEvent(Integration event);

  void addDependencyEvent(Dependency event);

  void addMetricEvent(Metric event);

  void addDistributionSeriesEvent(DistributionSeries event);

  void addLogMessageEvent(LogMessage event);

  EventSink NOOP = new Noop();

  class Noop implements EventSink {
    private Noop() {}

    @Override
    public void addConfigChangeEvent(ConfigChange event) {}

    @Override
    public void addIntegrationEvent(Integration event) {}

    @Override
    public void addDependencyEvent(Dependency event) {}

    @Override
    public void addMetricEvent(Metric event) {}

    @Override
    public void addDistributionSeriesEvent(DistributionSeries event) {}

    @Override
    public void addLogMessageEvent(LogMessage event) {}
  }
}
