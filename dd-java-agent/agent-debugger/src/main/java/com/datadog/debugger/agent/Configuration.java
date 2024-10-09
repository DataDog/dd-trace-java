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

  private final Collection<MetricProbe> metricProbes;
  private final Collection<LogProbe> logProbes;
  private final Collection<SpanProbe> spanProbes;
  private final Collection<TriggerProbe> triggerProbes;
  private final Collection<SpanDecorationProbe> spanDecorationProbes;
  private final FilterList allowList;
  private final FilterList denyList;
  private final LogProbe.Sampling sampling;

  public Configuration(String service, Collection<LogProbe> logProbes) {
    this(service, null, logProbes, null);
  }

  public Configuration(
      String serviceName,
      Collection<MetricProbe> metricProbes,
      Collection<LogProbe> logProbes,
      Collection<SpanProbe> spanProbes) {
    this(serviceName, metricProbes, logProbes, spanProbes, null, null, null, null, null);
  }

  public Configuration(
      String serviceName,
      Collection<MetricProbe> metricProbes,
      Collection<LogProbe> logProbes,
      Collection<SpanProbe> spanProbes,
      Collection<TriggerProbe> triggerProbes,
      Collection<SpanDecorationProbe> spanDecorationProbes,
      FilterList allowList,
      FilterList denyList,
      LogProbe.Sampling sampling) {
    this.service = serviceName;
    this.metricProbes = metricProbes;
    this.logProbes = logProbes;
    this.spanProbes = spanProbes;
    this.triggerProbes = triggerProbes;
    this.spanDecorationProbes = spanDecorationProbes;
    this.allowList = allowList;
    this.denyList = denyList;
    this.sampling = sampling;
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

  public Collection<ProbeDefinition> getDefinitions() {
    Collection<ProbeDefinition> result = new ArrayList<>();
    if (triggerProbes != null) {
      result.addAll(triggerProbes);
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
    if (spanDecorationProbes != null) {
      result.addAll(spanDecorationProbes);
    }
    return result;
  }

  @Generated
  @Override
  public String toString() {
    return "DebuggerConfiguration{"
        + "service="
        + service
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
        && Objects.equals(metricProbes, that.metricProbes)
        && Objects.equals(logProbes, that.logProbes)
        && Objects.equals(allowList, that.allowList)
        && Objects.equals(denyList, that.denyList)
        && Objects.equals(sampling, that.sampling);
  }

  @Generated
  @Override
  public int hashCode() {
    return Objects.hash(service, metricProbes, logProbes, allowList, denyList, sampling);
  }

  public static Configuration.Builder builder() {
    return new Configuration.Builder();
  }

  public static class Builder {
    private String service = null;
    private List<MetricProbe> metricProbes = null;
    private List<LogProbe> logProbes = null;
    private List<SpanProbe> spanProbes = null;
    private List<TriggerProbe> triggerProbes = null;
    private List<SpanDecorationProbe> spanDecorationProbes = null;
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
      for (ProbeDefinition definition : definitions) {
        if (definition instanceof MetricProbe) add((MetricProbe) definition);
        if (definition instanceof TriggerProbe) add((TriggerProbe) definition);
        if (definition instanceof LogProbe) add((LogProbe) definition);
        if (definition instanceof SpanProbe) add((SpanProbe) definition);
        if (definition instanceof SpanDecorationProbe) add((SpanDecorationProbe) definition);
      }
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

    public Configuration.Builder add(SpanProbe probe) {
      if (spanProbes == null) {
        spanProbes = new ArrayList<>();
      }
      spanProbes.add(probe);
      return this;
    }

    public Configuration.Builder add(TriggerProbe probe) {
      if (triggerProbes == null) {
        triggerProbes = new ArrayList<>();
      }
      triggerProbes.add(probe);
      return this;
    }

    public Configuration.Builder add(SpanDecorationProbe probe) {
      if (spanDecorationProbes == null) {
        spanDecorationProbes = new ArrayList<>();
      }
      spanDecorationProbes.add(probe);
      return this;
    }

    public Configuration.Builder add(LogProbe.Sampling newSampling) {
      if (newSampling != null) {
        sampling = newSampling;
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

    public Builder addExceptionProbes(Collection<ExceptionProbe> probes) {
      if (probes == null) {
        return this;
      }
      for (ExceptionProbe probe : probes) {
        add(probe);
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

    public Configuration.Builder addTriggerProbes(Collection<TriggerProbe> probes) {
      if (probes == null) {
        return this;
      }
      for (TriggerProbe probe : probes) {
        add(probe);
      }
      return this;
    }

    public Configuration.Builder addSpanDecorationProbes(Collection<SpanDecorationProbe> probes) {
      if (probes == null) {
        return this;
      }
      for (SpanDecorationProbe probe : probes) {
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

    public Configuration.Builder setSampling(LogProbe.Sampling sampling) {
      this.sampling = sampling;
      return this;
    }

    public Configuration.Builder add(Configuration other) {
      if (other.service != null) {
        this.service = other.service;
      }
      addMetricProbes(other.getMetricProbes());
      addLogProbes(other.getLogProbes());
      addSpanProbes(other.getSpanProbes());
      addTriggerProbes(other.getTriggerProbes());
      addSpanDecorationProbes(other.getSpanDecorationProbes());
      addAllowList(other.getAllowList());
      addDenyList(other.getDenyList());
      add(other.getSampling());
      return this;
    }

    public Configuration build() {
      return new Configuration(
          service,
          metricProbes,
          logProbes,
          spanProbes,
          triggerProbes,
          spanDecorationProbes,
          allowList,
          denyList,
          sampling);
    }
  }
}
