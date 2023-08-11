package datadog.telemetry;

import datadog.telemetry.api.ConfigChange;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.dependency.Dependency;
import java.util.Queue;

interface EventSource {
  boolean isEmpty();

  ConfigChange nextConfigChangeEvent();

  Integration nextIntegrationEvent();

  Dependency nextDependencyEvent();

  Metric nextMetricEvent();

  DistributionSeries nextDistributionSeriesEvent();

  LogMessage nextLogMessageEvent();

  final class Queued implements EventSource {
    private final Queue<ConfigChange> configChangeQueue;
    private final Queue<Integration> integrationQueue;
    private final Queue<Dependency> dependencyQueue;
    private final Queue<Metric> metricQueue;
    private final Queue<DistributionSeries> distributionSeriesQueue;
    private final Queue<LogMessage> logMessageQueue;

    Queued(
        Queue<ConfigChange> configChangeQueue,
        Queue<Integration> integrationQueue,
        Queue<Dependency> dependencyQueue,
        Queue<Metric> metricQueue,
        Queue<DistributionSeries> distributionSeriesQueue,
        Queue<LogMessage> logMessageQueue) {
      this.configChangeQueue = configChangeQueue;
      this.integrationQueue = integrationQueue;
      this.dependencyQueue = dependencyQueue;
      this.metricQueue = metricQueue;
      this.distributionSeriesQueue = distributionSeriesQueue;
      this.logMessageQueue = logMessageQueue;
    }

    @Override
    public boolean isEmpty() {
      return configChangeQueue.isEmpty()
          && integrationQueue.isEmpty()
          && dependencyQueue.isEmpty()
          && metricQueue.isEmpty()
          && distributionSeriesQueue.isEmpty()
          && logMessageQueue.isEmpty();
    }

    @Override
    public ConfigChange nextConfigChangeEvent() {
      return configChangeQueue.poll();
    }

    @Override
    public Integration nextIntegrationEvent() {
      return integrationQueue.poll();
    }

    @Override
    public Dependency nextDependencyEvent() {
      return dependencyQueue.poll();
    }

    @Override
    public Metric nextMetricEvent() {
      return metricQueue.poll();
    }

    @Override
    public DistributionSeries nextDistributionSeriesEvent() {
      return distributionSeriesQueue.poll();
    }

    @Override
    public LogMessage nextLogMessageEvent() {
      return logMessageQueue.poll();
    }
  }
}
