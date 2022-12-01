package com.datadog.debugger.agent;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SnapshotProbe;
import com.squareup.moshi.Json;
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

  @Json(name = "id")
  private final String service;

  private final Collection<SnapshotProbe> snapshotProbes;
  private final Collection<MetricProbe> metricProbes;
  private final Collection<LogProbe> logProbes;
  private final FilterList allowList;
  private final FilterList denyList;
  private final SnapshotProbe.Sampling sampling;

  public Configuration(String service, Collection<SnapshotProbe> snapshotProbes) {
    this(service, snapshotProbes, null, null);
  }

  public Configuration(
      String serviceName,
      Collection<SnapshotProbe> snapshotProbes,
      Collection<MetricProbe> metricProbes,
      Collection<LogProbe> logProbes) {
    this(serviceName, snapshotProbes, metricProbes, logProbes, null, null, null);
  }

  public Configuration(
      String serviceName,
      Collection<SnapshotProbe> snapshotProbes,
      Collection<MetricProbe> metricProbes,
      Collection<LogProbe> logProbes,
      FilterList allowList,
      FilterList denyList,
      SnapshotProbe.Sampling sampling) {
    this.service = serviceName;
    this.snapshotProbes = snapshotProbes;
    this.metricProbes = metricProbes;
    this.logProbes = logProbes;
    this.allowList = allowList;
    this.denyList = denyList;
    this.sampling = sampling;
  }

  public String getService() {
    return service;
  }

  public Collection<SnapshotProbe> getSnapshotProbes() {
    return snapshotProbes;
  }

  public Collection<MetricProbe> getMetricProbes() {
    return metricProbes;
  }

  public Collection<LogProbe> getLogProbes() {
    return logProbes;
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

  public Collection<ProbeDefinition> getDefinitions() {
    Collection<ProbeDefinition> result = new ArrayList<>();
    if (snapshotProbes != null) {
      result.addAll(snapshotProbes);
    }
    if (metricProbes != null) {
      result.addAll(metricProbes);
    }
    if (logProbes != null) {
      result.addAll(logProbes);
    }
    return result;
  }

  @Generated
  @Override
  public String toString() {
    return "DebuggerConfiguration{"
        + "service="
        + service
        + ", snapshotProbes="
        + snapshotProbes
        + ", metricProbes="
        + metricProbes
        + ", logProbes="
        + logProbes
        + ", allowList="
        + allowList
        + ", denyList="
        + denyList
        + ", sampling="
        + sampling
        + '}';
  }

  @Generated
  @Override
  public boolean equals(Object o) {
    if (this == o) return true;
    if (o == null || getClass() != o.getClass()) return false;
    Configuration that = (Configuration) o;
    return Objects.equals(service, that.service)
        && Objects.equals(snapshotProbes, that.snapshotProbes)
        && Objects.equals(metricProbes, that.metricProbes)
        && Objects.equals(logProbes, that.logProbes)
        && Objects.equals(allowList, that.allowList)
        && Objects.equals(denyList, that.denyList)
        && Objects.equals(sampling, that.sampling);
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hash(
        service, snapshotProbes, metricProbes, logProbes, allowList, denyList, sampling);
  }

  public static Configuration.Builder builder() {
    return new Configuration.Builder();
  }

  public static class Builder {
    private String service = null;
    private List<SnapshotProbe> snapshotProbes = null;
    private List<MetricProbe> metricProbes = null;
    private List<LogProbe> logProbes = null;
    private FilterList allowList = null;
    private FilterList denyList = null;
    private SnapshotProbe.Sampling sampling = null;

    public Configuration.Builder setService(String service) {
      this.service = service;
      return this;
    }

    public Configuration.Builder add(SnapshotProbe probe) {
      if (snapshotProbes == null) {
        snapshotProbes = new ArrayList<>();
      }
      snapshotProbes.add(probe);
      return this;
    }

    public Configuration.Builder add(MetricProbe probe) {
      if (metricProbes == null) {
        metricProbes = new ArrayList<>();
      }
      metricProbes.add(probe);
      return this;
    }

    public Configuration.Builder add(LogProbe probe) {
      if (logProbes == null) {
        logProbes = new ArrayList<>();
      }
      logProbes.add(probe);
      return this;
    }

    public Configuration.Builder add(SnapshotProbe.Sampling newSampling) {
      if (newSampling != null) {
        sampling = newSampling;
      }
      return this;
    }

    public Configuration.Builder addSnapshotsProbes(Collection<SnapshotProbe> probes) {
      if (probes == null) {
        return this;
      }
      for (SnapshotProbe probe : probes) {
        add(probe);
      }
      return this;
    }

    public Configuration.Builder addMetricProbes(Collection<MetricProbe> probes) {
      if (probes == null) {
        return this;
      }
      for (MetricProbe probe : probes) {
        add(probe);
      }
      return this;
    }

    public Configuration.Builder addLogProbes(Collection<LogProbe> probes) {
      if (probes == null) {
        return this;
      }
      for (LogProbe probe : probes) {
        add(probe);
      }
      return this;
    }

    public Configuration.Builder addAllowList(FilterList newAllowList) {
      if (newAllowList == null) {
        return this;
      }
      if (allowList == null) {
        allowList = new FilterList(new ArrayList<>(), new ArrayList<>());
      }
      allowList.getClasses().addAll(newAllowList.getClasses());
      allowList.getPackagePrefixes().addAll(newAllowList.getPackagePrefixes());
      return this;
    }

    public Configuration.Builder addDenyList(FilterList newDenyList) {
      if (newDenyList == null) {
        return this;
      }
      if (denyList == null) {
        denyList = new FilterList(new ArrayList<>(), new ArrayList<>());
      }
      denyList.getClasses().addAll(newDenyList.getClasses());
      denyList.getPackagePrefixes().addAll(newDenyList.getPackagePrefixes());
      return this;
    }

    public Configuration.Builder add(Configuration other) {
      if (other.service != null) {
        this.service = other.service;
      }
      addSnapshotsProbes(other.getSnapshotProbes());
      addMetricProbes(other.getMetricProbes());
      addLogProbes(other.getLogProbes());
      addAllowList(other.getAllowList());
      addDenyList(other.getDenyList());
      add(other.getSampling());
      return this;
    }

    public Configuration build() {
      return new Configuration(
          service, snapshotProbes, metricProbes, logProbes, allowList, denyList, sampling);
    }
  }
}
