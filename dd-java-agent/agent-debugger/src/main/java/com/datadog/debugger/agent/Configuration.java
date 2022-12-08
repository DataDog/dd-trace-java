package com.datadog.debugger.agent;

import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SnapshotProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.squareup.moshi.Json;
import datadog.trace.bootstrap.debugger.Snapshot;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
  private final Collection<SpanProbe> spanProbes;
  private final FilterList allowList;
  private final FilterList denyList;
  private final SnapshotProbe.Sampling sampling;
  private final Map<String, Snapshot.ProbeSource> probesSource;

  public Configuration(String service) {
    this(service, null, null, null, null);
  }

  public Configuration(
      String serviceName,
      Collection<SnapshotProbe> snapshotProbes,
      Collection<MetricProbe> metricProbes,
      Collection<LogProbe> logProbes,
      Collection<SpanProbe> spanProbes) {
    this(serviceName, snapshotProbes, metricProbes, logProbes, spanProbes, null, null, null);
  }

  public Configuration(
      String serviceName,
      Collection<SnapshotProbe> snapshotProbes,
      Collection<MetricProbe> metricProbes,
      Collection<LogProbe> logProbes,
      Collection<SpanProbe> spanProbes,
      FilterList allowList,
      FilterList denyList,
      SnapshotProbe.Sampling sampling) {
    this(serviceName, snapshotProbes, metricProbes, logProbes, allowList, denyList, sampling, null);
  }

  public Configuration(
      String serviceName,
      Collection<SnapshotProbe> snapshotProbes,
      Collection<MetricProbe> metricProbes,
      Collection<LogProbe> logProbes,
      FilterList allowList,
      FilterList denyList,
      SnapshotProbe.Sampling sampling,
      Map<String, Snapshot.ProbeSource> probesSource) {
    this.service = serviceName;
    this.snapshotProbes = snapshotProbes;
    this.metricProbes = metricProbes;
    this.logProbes = logProbes;
    this.spanProbes = spanProbes;
    this.allowList = allowList;
    this.denyList = denyList;
    this.sampling = sampling;
    this.probesSource = probesSource;
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

  public Collection<SpanProbe> getSpanProbes() {
    return spanProbes;
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
    if (spanProbes != null) {
      result.addAll(spanProbes);
    }
    return result;
  }

  public Map<String, Snapshot.ProbeSource> getProbesSource() {
    return probesSource;
  }

  public Snapshot.ProbeSource getProbeSource(ProbeDefinition definition) {
    if (probesSource == null) {
      return null;
    }
    return probesSource.get(definition.getId());
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
        + ", probesSource="
        + probesSource
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
        && Objects.equals(sampling, that.sampling)
        && Objects.equals(probesSource, that.probesSource);
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hash(
        service,
        snapshotProbes,
        metricProbes,
        logProbes,
        allowList,
        denyList,
        sampling,
        probesSource);
  }

  public static Configuration.Builder builder() {
    return new Configuration.Builder();
  }

  public static class Builder {
    private String service = null;
    private List<SnapshotProbe> snapshotProbes = null;
    private List<MetricProbe> metricProbes = null;
    private List<LogProbe> logProbes = null;
    private List<SpanProbe> spanProbes = null;
    private FilterList allowList = null;
    private FilterList denyList = null;
    private SnapshotProbe.Sampling sampling = null;
    private Map<String, Snapshot.ProbeSource> probesSource = null;

    public Configuration.Builder setService(String service) {
      this.service = service;
      return this;
    }

    private void setProbeSource(ProbeDefinition probe, Snapshot.ProbeSource source) {
      if (source == null) {
        if (probesSource != null) {
          probesSource.remove(probe.getId());
        }
        return;
      }

      if (probesSource == null) {
        probesSource = new HashMap<>();
      }
      probesSource.put(probe.getId(), source);
    }

    public Configuration.Builder add(SnapshotProbe probe) {
      return this.add(probe, null);
    }

    public Configuration.Builder add(SnapshotProbe probe, Snapshot.ProbeSource source) {
      if (snapshotProbes == null) {
        snapshotProbes = new ArrayList<>();
      }
      snapshotProbes.add(probe);
      setProbeSource(probe, source);
      return this;
    }

    public Configuration.Builder add(MetricProbe probe) {
      return this.add(probe, null);
    }

    public Configuration.Builder add(MetricProbe probe, Snapshot.ProbeSource source) {
      if (metricProbes == null) {
        metricProbes = new ArrayList<>();
      }
      metricProbes.add(probe);
      setProbeSource(probe, source);
      return this;
    }

    public Configuration.Builder add(LogProbe probe) {
      return this.add(probe, null);
    }

    public Configuration.Builder add(LogProbe probe, Snapshot.ProbeSource source) {
      if (logProbes == null) {
        logProbes = new ArrayList<>();
      }
      logProbes.add(probe);
      setProbeSource(probe, source);
      return this;
    }

    public Configuration.Builder add(SpanProbe probe) {
      if (spanProbes == null) {
        spanProbes = new ArrayList<>();
      }
      spanProbes.add(probe);
      return this;
    }

    public Configuration.Builder add(SnapshotProbe.Sampling newSampling) {
      if (newSampling != null) {
        sampling = newSampling;
      }
      return this;
    }

    public Configuration.Builder addSnapshotsProbes(Collection<SnapshotProbe> probes) {
      return addSnapshotsProbes(probes, null);
    }

    public Configuration.Builder addSnapshotsProbes(
        Collection<SnapshotProbe> probes, Snapshot.ProbeSource source) {
      if (probes == null) {
        return this;
      }
      for (SnapshotProbe probe : probes) {
        add(probe, source);
      }
      return this;
    }

    public Configuration.Builder addMetricProbes(Collection<MetricProbe> probes) {
      return addMetricProbes(probes, null);
    }

    public Configuration.Builder addMetricProbes(
        Collection<MetricProbe> probes, Snapshot.ProbeSource source) {
      if (probes == null) {
        return this;
      }
      for (MetricProbe probe : probes) {
        add(probe, source);
      }
      return this;
    }

    public Configuration.Builder addLogProbes(Collection<LogProbe> probes) {
      return addLogProbes(probes, null);
    }

    public Configuration.Builder addLogProbes(
        Collection<LogProbe> probes, Snapshot.ProbeSource source) {
      if (probes == null) {
        return this;
      }
      for (LogProbe probe : probes) {
        add(probe, source);
      }
      return this;
    }

    public Configuration.Builder addSpanProbes(Collection<SpanProbe> probes) {
      if (probes == null) {
        return this;
      }
      for (SpanProbe probe : probes) {
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

    public Configuration.Builder setSampling(SnapshotProbe.Sampling sampling) {
      this.sampling = sampling;
      return this;
    }

    public Configuration.Builder add(Configuration other) {
      return add(other, null);
    }

    public Configuration.Builder add(Configuration other, Snapshot.ProbeSource source) {
      if (other.service != null) {
        this.service = other.service;
      }
      addSnapshotsProbes(other.getSnapshotProbes(), source);
      addMetricProbes(other.getMetricProbes(), source);
      addLogProbes(other.getLogProbes(), source);
      addAllowList(other.getAllowList());
      addDenyList(other.getDenyList());
      add(other.getSampling());
      return this;
    }

    public Configuration build() {
      return new Configuration(
          service,
          snapshotProbes,
          metricProbes,
          logProbes,
          spanProbes,
          allowList,
          denyList,
          sampling,
          probesSource);
    }
  }
}
