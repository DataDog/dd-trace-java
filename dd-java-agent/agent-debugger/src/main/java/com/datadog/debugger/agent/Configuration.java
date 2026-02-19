package com.datadog.debugger.agent;

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
import java.util.stream.Stream;
import datadog.trace.util.HashingUtils;

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
      return HashingUtils.hash(packagePrefixes, classes);
    }
  }

  @Json(name = "id")
  private final String service;

  private transient List<ProbeDefinition> probes = new ArrayList<>();
  private Collection<MetricProbe> metricProbes = new ArrayList<>();
  private Collection<LogProbe> logProbes = new ArrayList<>();
  private Collection<SpanProbe> spanProbes = new ArrayList<>();
  private Collection<TriggerProbe> triggerProbes = new ArrayList<>();
  private Collection<SpanDecorationProbe> spanDecorationProbes = new ArrayList<>();
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
    this.allowList = allowList;
    this.denyList = denyList;
    this.sampling = sampling;
    probes.forEach(this::add);
  }

  private void add(ProbeDefinition p) {
    if (p instanceof LogProbe) {
      logProbes.add((LogProbe) p);
    } else if (p instanceof MetricProbe) {
      metricProbes.add((MetricProbe) p);
    } else if (p instanceof SpanProbe) {
      spanProbes.add((SpanProbe) p);
    } else if (p instanceof SpanDecorationProbe) {
      spanDecorationProbes.add((SpanDecorationProbe) p);
    } else if (p instanceof TriggerProbe) {
      triggerProbes.add((TriggerProbe) p);
    } else {
      probes.add(p);
    }
  }

  public String getService() {
    return service;
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

  public Collection<TriggerProbe> getTriggerProbes() {
    return triggerProbes;
  }

  public Collection<SpanDecorationProbe> getSpanDecorationProbes() {
    return spanDecorationProbes;
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

  public List<ProbeDefinition> getDefinitions() {
    return Stream.of(
            triggerProbes, metricProbes, logProbes, spanProbes, spanDecorationProbes, probes)
        .filter(Objects::nonNull)
        .flatMap(Collection::stream)
        .collect(Collectors.toList());
  }

  @Generated
  @Override
  public String toString() {
    return "DebuggerConfiguration{"
        + "service="
        + service
        + ", probes="
        + getDefinitions()
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
    if (this == o) {
      return true;
    }
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
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
    return HashingUtils.hash(service, probes, allowList, denyList, sampling);
  }

  public static Configuration.Builder builder() {
    return new Configuration.Builder();
  }

  public static class Builder {
    private String service = null;

    private final List<ProbeDefinition> probes = new ArrayList<>();

    private FilterList allowList = null;

    private FilterList denyList = null;

    private LogProbe.Sampling sampling = null;

    public Configuration.Builder setService(String service) {
      this.service = service;
      return this;
    }

    public Configuration.Builder add(Collection<? extends ProbeDefinition> definitions) {
      if (definitions == null) {
        return this;
      }
      probes.addAll(definitions);
      return this;
    }

    public Configuration.Builder add(ProbeDefinition... probes) {
      for (ProbeDefinition probe : probes) {
        this.probes.add(probe);
      }
      return this;
    }

    public Configuration.Builder add(LogProbe.Sampling newSampling) {
      if (newSampling != null) {
        sampling = newSampling;
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

    public Configuration.Builder setSampling(LogProbe.Sampling sampling) {
      this.sampling = sampling;
      return this;
    }

    public Configuration build() {
      return new Configuration(service, probes, allowList, denyList, sampling);
    }
  }
}
