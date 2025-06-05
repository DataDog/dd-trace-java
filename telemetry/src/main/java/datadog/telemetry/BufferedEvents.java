package datadog.telemetry;

import datadog.telemetry.api.DistributionSeries;
import datadog.telemetry.api.Integration;
import datadog.telemetry.api.LogMessage;
import datadog.telemetry.api.Metric;
import datadog.telemetry.dependency.Dependency;
import datadog.trace.api.ConfigSetting;
import datadog.trace.api.telemetry.Endpoint;
import datadog.trace.api.telemetry.ProductChange;
import java.util.ArrayList;

/**
 * Keeps track of attempted to send telemetry events that can be used as a source for the next
 * telemetry request attempt or telemetry metric calculation.
 */
public final class BufferedEvents implements EventSource, EventSink {
  private static final int INITIAL_CAPACITY = 32;
  private ArrayList<ConfigSetting> configChangeEvents;
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
  private ArrayList<ProductChange> productChangeEvents;
  private int productChangeIndex;
  private ArrayList<Endpoint> endpointEvents;
  private int endpointIndex;

  public void addConfigChangeEvent(ConfigSetting event) {
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
  public void addProductChangeEvent(ProductChange event) {
    if (productChangeEvents == null) {
      productChangeEvents = new ArrayList<>(INITIAL_CAPACITY);
    }
    productChangeEvents.add(event);
  }

  @Override
  public void addEndpointEvent(final Endpoint event) {
    if (endpointEvents == null) {
      endpointEvents = new ArrayList<>(INITIAL_CAPACITY);
    }
    endpointEvents.add(event);
  }

  @Override
  public boolean hasConfigChangeEvent() {
    return configChangeEvents != null && configChangeIndex < configChangeEvents.size();
  }

  @Override
  public ConfigSetting nextConfigChangeEvent() {
    return configChangeEvents.get(configChangeIndex++);
  }

  @Override
  public boolean hasIntegrationEvent() {
    return integrationEvents != null && integrationIndex < integrationEvents.size();
  }

  @Override
  public Integration nextIntegrationEvent() {
    return integrationEvents.get(integrationIndex++);
  }

  @Override
  public boolean hasDependencyEvent() {
    return dependencyEvents != null && dependencyIndex < dependencyEvents.size();
  }

  @Override
  public Dependency nextDependencyEvent() {
    return dependencyEvents.get(dependencyIndex++);
  }

  @Override
  public boolean hasMetricEvent() {
    return metricEvents != null && metricIndex < metricEvents.size();
  }

  @Override
  public Metric nextMetricEvent() {
    return metricEvents.get(metricIndex++);
  }

  @Override
  public boolean hasDistributionSeriesEvent() {
    return distributionSeriesEvents != null
        && distributionSeriesIndex < distributionSeriesEvents.size();
  }

  @Override
  public DistributionSeries nextDistributionSeriesEvent() {
    return distributionSeriesEvents.get(distributionSeriesIndex++);
  }

  @Override
  public boolean hasLogMessageEvent() {
    return logMessageEvents != null && logMessageIndex < logMessageEvents.size();
  }

  @Override
  public LogMessage nextLogMessageEvent() {
    return logMessageEvents.get(logMessageIndex++);
  }

  @Override
  public boolean hasProductChangeEvent() {
    return productChangeEvents != null && productChangeIndex < productChangeEvents.size();
  }

  @Override
  public ProductChange nextProductChangeEvent() {
    return productChangeEvents.get(productChangeIndex++);
  }

  @Override
  public boolean hasEndpoint() {
    return endpointEvents != null && endpointIndex < endpointEvents.size();
  }

  @Override
  public Endpoint nextEndpoint() {
    return endpointEvents.get(endpointIndex++);
  }
}
