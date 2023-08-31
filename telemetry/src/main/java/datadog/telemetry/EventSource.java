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

  boolean hasConfigChangeEvent();

  ConfigChange nextConfigChangeEvent();

  boolean hasIntegrationEvent();

  Integration nextIntegrationEvent();

  boolean hasDependencyEvent();

  Dependency nextDependencyEvent();

  boolean hasMetricEvent();

  Metric nextMetricEvent();

  boolean hasDistributionSeriesEvent();

  DistributionSeries nextDistributionSeriesEvent();

  boolean hasLogMessageEvent();

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
      return !hasConfigChangeEvent()
          && integrationQueue.isEmpty()
          && dependencyQueue.isEmpty()
          && metricQueue.isEmpty()
          && distributionSeriesQueue.isEmpty()
          && logMessageQueue.isEmpty();
    }

    @Override
    public boolean hasConfigChangeEvent() {
      return !configChangeQueue.isEmpty();
    }

    @Override
    public ConfigChange nextConfigChangeEvent() {
      return configChangeQueue.poll();
    }

    @Override
    public boolean hasIntegrationEvent() {
      return !integrationQueue.isEmpty();
    }

    @Override
    public Integration nextIntegrationEvent() {
      return integrationQueue.poll();
    }

    @Override
    public boolean hasDependencyEvent() {
      return !dependencyQueue.isEmpty();
    }

    @Override
    public Dependency nextDependencyEvent() {
      return dependencyQueue.poll();
    }

    @Override
    public boolean hasMetricEvent() {
      return !metricQueue.isEmpty();
    }

    @Override
    public Metric nextMetricEvent() {
      return metricQueue.poll();
    }

    @Override
    public boolean hasDistributionSeriesEvent() {
      return !distributionSeriesQueue.isEmpty();
    }

    @Override
    public DistributionSeries nextDistributionSeriesEvent() {
      return distributionSeriesQueue.poll();
    }

    @Override
    public boolean hasLogMessageEvent() {
      return !logMessageQueue.isEmpty();
    }

    @Override
    public LogMessage nextLogMessageEvent() {
      return logMessageQueue.poll();
    }
  }
}
