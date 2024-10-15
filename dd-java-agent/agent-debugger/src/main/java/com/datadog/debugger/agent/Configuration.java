package com.datadog.debugger.agent;

import com.datadog.debugger.probe.ExceptionProbe;
import com.datadog.debugger.probe.LogProbe;
import com.datadog.debugger.probe.MetricProbe;
import com.datadog.debugger.probe.ProbeDefinition;
import com.datadog.debugger.probe.SpanDecorationProbe;
import com.datadog.debugger.probe.SpanProbe;
import com.datadog.debugger.probe.TriggerProbe;
import com.squareup.moshi.Json;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

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

  private final List<ProbeDefinition> probes = new ArrayList<>();
  private final FilterList allowList;
  private final FilterList denyList;
  private final LogProbe.Sampling sampling;

  public Configuration(String serviceName, List<? extends ProbeDefinition> probes) {
    this(serviceName, probes, null, null, null);
  }

  public Configuration(
      String serviceName,
      List<? extends ProbeDefinition> probes,
      FilterList allowList,
      FilterList denyList,
      LogProbe.Sampling sampling) {
    this.service = serviceName;
    this.probes.addAll(probes);
    this.allowList = allowList;
    this.denyList = denyList;
    this.sampling = sampling;
  }

  public String getService() {
    return service;
  }

  @SuppressWarnings("unchecked")
  public <T extends ProbeDefinition> List<T> getProbes(Class<T> type) {
    return (List<T>)
        probes.stream()
            .filter(p -> type.isAssignableFrom(p.getClass()))
            .collect(Collectors.toList());
  }

  public Collection<? extends MetricProbe> getMetricProbes() {
    return getProbes(MetricProbe.class);
  }

  public Collection<LogProbe> getLogProbes() {
    return getProbes(LogProbe.class);
  }

  public Collection<SpanProbe> getSpanProbes() {
    return getProbes(SpanProbe.class);
  }

  public Collection<TriggerProbe> getTriggerProbes() {
    return getProbes(TriggerProbe.class);
  }

  public Collection<SpanDecorationProbe> getSpanDecorationProbes() {
    return getProbes(SpanDecorationProbe.class);
  }

  public FilterList getAllowList() {
    return allowList;
  }

  public FilterList getDenyList() {
    return denyList;
  }

  public LogProbe.Sampling getSampling() {
    return sampling;
  }

  public Collection<ProbeDefinition> getDefinitions() {
    return probes;
  }

  @Generated
  @Override
  public String toString() {
    return "DebuggerConfiguration{"
        + "service="
        + service
        + ", probes="
        + probes
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
        && Objects.equals(probes, that.probes)
        && Objects.equals(allowList, that.allowList)
        && Objects.equals(denyList, that.denyList)
        && Objects.equals(sampling, that.sampling);
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hash(service, probes, allowList, denyList, sampling);
  }

  public static Configuration.Builder builder() {
    return new Configuration.Builder();
  }

  public static class Builder {
    private String service = null;
    private List<ProbeDefinition> probes = new ArrayList<>();
    private FilterList allowList = null;
    private FilterList denyList = null;
    private LogProbe.Sampling sampling = null;

    public Configuration.Builder setService(String service) {
      this.service = service;
      return this;
    }

    public Configuration.Builder add(ProbeDefinition probe) {
      probes.add(probe);
      return this;
    }

    public Configuration.Builder add(Collection<? extends ProbeDefinition> definitions) {
      if (definitions == null) {
        return this;
      }
      for (ProbeDefinition definition : definitions) {
        probes.add(definition);
      }
      return this;
    }

    public Configuration.Builder add(LogProbe.Sampling newSampling) {
      if (newSampling != null) {
        sampling = newSampling;
      }
      return this;
    }

    public Configuration.Builder addMetricProbes(Collection<MetricProbe> probes) {
      return add(probes);
    }

    public Configuration.Builder addLogProbes(Collection<LogProbe> probes) {
      return add(probes);
    }

    public Builder addExceptionProbes(Collection<ExceptionProbe> probes) {
      return add(probes);
    }

    public Configuration.Builder addSpanProbes(Collection<SpanProbe> probes) {
      return add(probes);
    }

    public Configuration.Builder addTriggerProbes(Collection<TriggerProbe> probes) {
      return add(probes);
    }

    public Configuration.Builder addSpanDecorationProbes(Collection<SpanDecorationProbe> probes) {
      return add(probes);
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

    public Configuration.Builder setSampling(LogProbe.Sampling sampling) {
      this.sampling = sampling;
      return this;
    }

    public Configuration.Builder add(Configuration other) {
      if (other.service != null) {
        this.service = other.service;
      }
      add(other.getDefinitions());
      addAllowList(other.getAllowList());
      addDenyList(other.getDenyList());
      add(other.getSampling());
      return this;
    }

    public Configuration build() {
      return new Configuration(service, probes, allowList, denyList, sampling);
    }
  }
}
