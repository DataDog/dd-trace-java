package datadog.telemetry;

import datadog.telemetry.api.ConfigChange;
import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.dependency.Dependency;
import java.util.ArrayList;

/**
 * Keeps track of attempted to send telemetry events that can be used as a source for the next
 * telemetry request attempt.
 */
public final class BufferedEvents implements EventSource, EventSink {
  private static final int INITIAL_CAPACITY = 36;
  private ArrayList<ConfigChange> configChangeEvents;
  private int configChangeIndex;
  private ArrayList<Integration> integrationEvents;
  private int integrationIndex;
  private ArrayList<Dependency> dependencyEvents;
  private int dependencyIndex;
  private ArrayList<Metric> metricEvents;
  private int metricIndex;
  private ArrayList<DistributionSeries> distributionSeriesEvents;
  private int distributionSeriesIndex;
  private ArrayList<LogMessage> logMessageEvents;
  private int logMessageIndex;

  @Override
  public boolean isEmpty() {
    return (configChangeEvents == null || configChangeEvents.isEmpty())
        && (integrationEvents == null || integrationEvents.isEmpty())
        && (dependencyEvents == null || dependencyEvents.isEmpty())
        && (metricEvents == null || metricEvents.isEmpty())
        && (distributionSeriesEvents == null || distributionSeriesEvents.isEmpty())
        && (logMessageEvents == null || logMessageEvents.isEmpty());
  }

  public void addConfigChangeEvent(ConfigChange event) {
    if (configChangeEvents == null) {
      configChangeEvents = new ArrayList<>(INITIAL_CAPACITY);
    }
    configChangeEvents.add(event);
  }

  @Override
  public void addIntegrationEvent(Integration event) {
    if (integrationEvents == null) {
      integrationEvents = new ArrayList<>(INITIAL_CAPACITY);
    }
    integrationEvents.add(event);
  }

  @Override
  public void addDependencyEvent(Dependency event) {
    if (dependencyEvents == null) {
      dependencyEvents = new ArrayList<>(INITIAL_CAPACITY);
    }
    dependencyEvents.add(event);
  }

  @Override
  public void addMetricEvent(Metric event) {
    if (metricEvents == null) {
      metricEvents = new ArrayList<>(INITIAL_CAPACITY);
    }
    metricEvents.add(event);
  }

  @Override
  public void addDistributionSeriesEvent(DistributionSeries event) {
    if (distributionSeriesEvents == null) {
      distributionSeriesEvents = new ArrayList<>(INITIAL_CAPACITY);
    }
    distributionSeriesEvents.add(event);
  }

  @Override
  public void addLogMessageEvent(LogMessage event) {
    if (logMessageEvents == null) {
      logMessageEvents = new ArrayList<>(INITIAL_CAPACITY);
    }
    logMessageEvents.add(event);
  }

  @Override
  public ConfigChange nextConfigChangeEvent() {
    if (configChangeEvents == null || configChangeIndex == configChangeEvents.size()) {
      return null;
    }
    return configChangeEvents.get(configChangeIndex++);
  }

  @Override
  public Integration nextIntegrationEvent() {
    if (integrationEvents == null || integrationIndex == integrationEvents.size()) {
      return null;
    }
    return integrationEvents.get(integrationIndex++);
  }

  @Override
  public Dependency nextDependencyEvent() {
    if (dependencyEvents == null || dependencyIndex == dependencyEvents.size()) {
      return null;
    }
    return dependencyEvents.get(dependencyIndex++);
  }

  @Override
  public Metric nextMetricEvent() {
    if (metricEvents == null || metricIndex == metricEvents.size()) {
      return null;
    }
    return metricEvents.get(metricIndex++);
  }

  @Override
  public DistributionSeries nextDistributionSeriesEvent() {
    if (distributionSeriesEvents == null
        || distributionSeriesIndex == distributionSeriesEvents.size()) {
      return null;
    }
    return distributionSeriesEvents.get(distributionSeriesIndex++);
  }

  @Override
  public LogMessage nextLogMessageEvent() {
    if (logMessageEvents == null || logMessageIndex == logMessageEvents.size()) {
      return null;
    }
    return logMessageEvents.get(logMessageIndex++);
  }
}
