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
    return isConfigEventsEmpty()
        && isIntegrationEventsEmpty()
        && isDependencyEventsEmpty()
        && isMetricEventsEmpty()
        && isDistributionSeriesEmpty()
        && isLogMessageEventsEmpty();
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
    if (isConfigEventsEmpty()) {
      return null;
    }
    return configChangeEvents.get(configChangeIndex++);
  }

  private boolean isConfigEventsEmpty() {
    return configChangeEvents == null || configChangeIndex == configChangeEvents.size();
  }

  @Override
  public Integration nextIntegrationEvent() {
    if (isIntegrationEventsEmpty()) {
      return null;
    }
    return integrationEvents.get(integrationIndex++);
  }

  private boolean isIntegrationEventsEmpty() {
    return integrationEvents == null || integrationIndex == integrationEvents.size();
  }

  @Override
  public Dependency nextDependencyEvent() {
    if (isDependencyEventsEmpty()) {
      return null;
    }
    return dependencyEvents.get(dependencyIndex++);
  }

  private boolean isDependencyEventsEmpty() {
    return dependencyEvents == null || dependencyIndex == dependencyEvents.size();
  }

  @Override
  public Metric nextMetricEvent() {
    if (isMetricEventsEmpty()) {
      return null;
    }
    return metricEvents.get(metricIndex++);
  }

  private boolean isMetricEventsEmpty() {
    return metricEvents == null || metricIndex == metricEvents.size();
  }

  @Override
  public DistributionSeries nextDistributionSeriesEvent() {
    if (isDistributionSeriesEmpty()) {
      return null;
    }
    return distributionSeriesEvents.get(distributionSeriesIndex++);
  }

  private boolean isDistributionSeriesEmpty() {
    return distributionSeriesEvents == null
        || distributionSeriesIndex == distributionSeriesEvents.size();
  }

  @Override
  public LogMessage nextLogMessageEvent() {
    if (isLogMessageEventsEmpty()) {
      return null;
    }
    return logMessageEvents.get(logMessageIndex++);
  }

  private boolean isLogMessageEventsEmpty() {
    return logMessageEvents == null || logMessageIndex == logMessageEvents.size();
  }
}
