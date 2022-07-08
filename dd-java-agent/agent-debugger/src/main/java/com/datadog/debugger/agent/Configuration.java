package com.datadog.debugger.agent;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Stores debugger configuration for a service with: - Probe definitions - filters (allow/deny) -
 * sampling
 */
public class Configuration {

  /** Stores classes & packages filtering (allow or deny lists) */
  public static class FilterList {
    private final List<String> packagePrefixes;
    private final List<String> classes;

    public FilterList(List<String> packagePrefixes, List<String> classes) {
      this.packagePrefixes = packagePrefixes;
      this.classes = classes;
    }

    public List<String> getPackagePrefixes() {
      return packagePrefixes;
    }

    public List<String> getClasses() {
      return classes;
    }

    @Generated
    @Override
    public String toString() {
      return "FilterList{" + "packagePrefixes=" + packagePrefixes + ", classes=" + classes + '}';
    }

    @Generated
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      FilterList allowList = (FilterList) o;
      return Objects.equals(packagePrefixes, allowList.packagePrefixes)
          && Objects.equals(classes, allowList.classes);
    }

    @Generated
    @Override
    public int hashCode() {
      return Objects.hash(packagePrefixes, classes);
    }
  }

  /** Stores operational configuration */
  public static class OpsConfiguration {
    private final long pollInterval;

    public OpsConfiguration(long pollInterval) {
      this.pollInterval = pollInterval;
    }

    public long getPollInterval() {
      return pollInterval;
    }

    public Duration getPollIntervalDuration() {
      return Duration.ofSeconds(pollInterval);
    }

    @Generated
    @Override
    public String toString() {
      return "OperationConfiguration{" + "pollInterval=" + pollInterval + '}';
    }

    @Generated
    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      OpsConfiguration other = (OpsConfiguration) o;
      return Objects.equals(pollInterval, other.pollInterval);
    }

    @Generated
    @Override
    public int hashCode() {
      return Objects.hash(pollInterval);
    }
  }

  private final String id;
  private final long orgId;
  private final Collection<SnapshotProbe> snapshotProbes;
  private final Collection<MetricProbe> metricProbes;
  private final FilterList allowList;
  private final FilterList denyList;
  private final SnapshotProbe.Sampling sampling;
  private final OpsConfiguration opsConfig;

  public Configuration(String id, long orgId, Collection<SnapshotProbe> snapshotProbes) {
    this(id, orgId, snapshotProbes, null, null, null, null, null);
  }

  public Configuration(
      String id,
      long orgId,
      Collection<SnapshotProbe> snapshotProbes,
      Collection<MetricProbe> metricProbes) {
    this(id, orgId, snapshotProbes, metricProbes, null, null, null, null);
  }

  public Configuration(
      String id,
      long orgId,
      Collection<SnapshotProbe> snapshotProbes,
      Collection<MetricProbe> metricProbes,
      FilterList allowList,
      FilterList denyList,
      SnapshotProbe.Sampling sampling,
      OpsConfiguration opsConfig) {
    this.id = id;
    this.orgId = orgId;
    this.snapshotProbes = snapshotProbes;
    this.metricProbes = metricProbes;
    this.allowList = allowList;
    this.denyList = denyList;
    this.sampling = sampling;
    this.opsConfig = opsConfig;
  }

  public String getId() {
    return id;
  }

  public long getOrgId() {
    return orgId;
  }

  public Collection<SnapshotProbe> getSnapshotProbes() {
    return snapshotProbes;
  }

  public Collection<MetricProbe> getMetricProbes() {
    return metricProbes;
  }

  public FilterList getAllowList() {
    return allowList;
  }

  public FilterList getDenyList() {
    return denyList;
  }

  public SnapshotProbe.Sampling getSampling() {
    return sampling;
  }

  public OpsConfiguration getOpsConfig() {
    return opsConfig;
  }

  public Collection<ProbeDefinition> getDefinitions() {
    Collection<ProbeDefinition> result = new ArrayList<>();
    if (snapshotProbes != null) {
      result.addAll(snapshotProbes);
    }
    if (metricProbes != null) {
      result.addAll(metricProbes);
    }
    return result;
  }

  @Generated
  @Override
  public String toString() {
    return "DebuggerConfiguration{"
        + "id='"
        + id
        + '\''
        + ", orgId="
        + orgId
        + ", probes="
        + snapshotProbes
        + ", metricProbes="
        + metricProbes
        + ", allowList="
        + allowList
        + ", denyList="
        + denyList
        + ", sampling="
        + sampling
        + ", opsConfig="
        + opsConfig
        + '}';
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Configuration that = (Configuration) o;
    return orgId == that.orgId
        && Objects.equals(id, that.id)
        && Objects.equals(snapshotProbes, that.snapshotProbes)
        && Objects.equals(metricProbes, that.metricProbes)
        && Objects.equals(allowList, that.allowList)
        && Objects.equals(denyList, that.denyList)
        && Objects.equals(sampling, that.sampling)
        && Objects.equals(opsConfig, that.opsConfig);
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hash(
        id, orgId, snapshotProbes, metricProbes, allowList, denyList, sampling, opsConfig);
  }
}
