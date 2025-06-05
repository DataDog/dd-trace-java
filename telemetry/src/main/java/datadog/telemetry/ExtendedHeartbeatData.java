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

public class ExtendedHeartbeatData {
  private static final int DEFAULT_DEPENDENCIES_LIMIT = 2000;
  private static final int INITIAL_CAPACITY = 32;

  private final int dependenciesLimit;
  private final ArrayList<ConfigSetting> configuration;
  private final ArrayList<Dependency> dependencies;
  private final ArrayList<Integration> integrations;

  public ExtendedHeartbeatData() {
    this(DEFAULT_DEPENDENCIES_LIMIT);
  }

  ExtendedHeartbeatData(int dependenciesLimit) {
    this.dependenciesLimit = dependenciesLimit;
    configuration = new ArrayList<>(INITIAL_CAPACITY);
    dependencies = new ArrayList<>(INITIAL_CAPACITY);
    integrations = new ArrayList<>(INITIAL_CAPACITY);
  }

  public void pushConfigSetting(ConfigSetting cs) {
    configuration.add(cs);
  }

  public void pushDependency(Dependency d) {
    if (dependencies.size() < dependenciesLimit) {
      dependencies.add(d);
    }
  }

  public void pushIntegration(Integration i) {
    integrations.add(i);
  }

  public EventSource snapshot() {
    return new Snapshot();
  }

  private final class Snapshot implements EventSource {
    private int configIndex;
    private int dependencyIndex;
    private int integrationIndex;

    @Override
    public boolean hasConfigChangeEvent() {
      return configIndex < configuration.size();
    }

    @Override
    public ConfigSetting nextConfigChangeEvent() {
      return configuration.get(configIndex++);
    }

    @Override
    public boolean hasIntegrationEvent() {
      return integrationIndex < integrations.size();
    }

    @Override
    public Integration nextIntegrationEvent() {
      return integrations.get(integrationIndex++);
    }

    @Override
    public boolean hasDependencyEvent() {
      return dependencyIndex < dependencies.size();
    }

    @Override
    public Dependency nextDependencyEvent() {
      return dependencies.get(dependencyIndex++);
    }

    @Override
    public boolean hasMetricEvent() {
      return false;
    }

    @Override
    public Metric nextMetricEvent() {
      return null;
    }

    @Override
    public boolean hasDistributionSeriesEvent() {
      return false;
    }

    @Override
    public DistributionSeries nextDistributionSeriesEvent() {
      return null;
    }

    @Override
    public boolean hasLogMessageEvent() {
      return false;
    }

    @Override
    public LogMessage nextLogMessageEvent() {
      return null;
    }

    @Override
    public boolean hasProductChangeEvent() {
      return false;
    }

    @Override
    public ProductChange nextProductChangeEvent() {
      return null;
    }

    @Override
    public boolean hasEndpoint() {
      return false;
    }

    @Override
    public Endpoint nextEndpoint() {
      return null;
    }
  }
}
