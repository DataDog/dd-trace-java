package datadog.trace.api;

import static datadog.trace.util.CollectionUtils.tryMakeImmutableMap;

import datadog.trace.api.sampling.SamplingRule.SpanSamplingRule;
import datadog.trace.api.sampling.SamplingRule.TraceSamplingRule;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Manages dynamic configuration for a particular {@link Tracer} instance. */
public final class DynamicConfig {
  private volatile Snapshot currentSnapshot;

  private DynamicConfig() {}

  public static Builder create() {
    return new DynamicConfig().prepare();
  }

  /** Captures a snapshot of the configuration at the start of a trace. */
  public TraceConfig captureTraceConfig() {
    return currentSnapshot;
  }

  public Builder prepare() {
    return new Builder(currentSnapshot);
  }

  public final class Builder {
    Map<String, String> serviceMapping;
    Map<String, String> taggedHeaders;
    Map<String, String> baggageMapping;
    List<? extends SpanSamplingRule> spanSamplingRules;
    List<? extends TraceSamplingRule> traceSamplingRules;

    Builder(Snapshot snapshot) {
      if (null == snapshot) {
        this.serviceMapping = Collections.emptyMap();
        this.taggedHeaders = Collections.emptyMap();
        this.baggageMapping = Collections.emptyMap();
        this.spanSamplingRules = Collections.emptyList();
        this.traceSamplingRules = Collections.emptyList();
      } else {
        this.serviceMapping = snapshot.serviceMapping;
        this.taggedHeaders = snapshot.taggedHeaders;
        this.baggageMapping = snapshot.baggageMapping;
        this.spanSamplingRules = snapshot.spanSamplingRules;
        this.traceSamplingRules = snapshot.traceSamplingRules;
      }
    }

    public Builder setServiceMapping(Map<String, String> serviceMapping) {
      this.serviceMapping = cleanMapping(serviceMapping, false, false);
      return this;
    }

    public Builder setTaggedHeaders(Map<String, String> taggedHeaders) {
      this.taggedHeaders = cleanMapping(taggedHeaders, true, true);
      return this;
    }

    public Builder setBaggageMapping(Map<String, String> baggageMapping) {
      this.baggageMapping = cleanMapping(baggageMapping, true, false);
      return this;
    }

    public Builder setSpanSamplingRules(List<? extends SpanSamplingRule> spanSamplingRules) {
      this.spanSamplingRules = spanSamplingRules;
      return this;
    }

    public Builder setTraceSamplingRules(List<? extends TraceSamplingRule> traceSamplingRules) {
      this.traceSamplingRules = traceSamplingRules;
      return this;
    }

    /** Overwrites the current configuration with a new snapshot. */
    public DynamicConfig apply() {
      DynamicConfig.this.currentSnapshot = new Snapshot(this);
      return DynamicConfig.this;
    }

    private Map<String, String> cleanMapping(
        Map<String, String> mapping, boolean lowerCaseKeys, boolean lowerCaseValues) {
      final Map<String, String> cleanedMapping = new HashMap<>(mapping.size() * 4 / 3);
      for (Map.Entry<String, String> association : mapping.entrySet()) {
        String key = association.getKey().trim();
        if (lowerCaseKeys) {
          key = key.toLowerCase();
        }
        String value = association.getValue().trim();
        if (lowerCaseValues) {
          value = value.toLowerCase();
        }
        cleanedMapping.put(key, value);
      }
      return tryMakeImmutableMap(cleanedMapping);
    }
  }

  /** Immutable snapshot of the configuration. */
  static final class Snapshot implements TraceConfig {
    final Map<String, String> serviceMapping;
    final Map<String, String> taggedHeaders;
    final Map<String, String> baggageMapping;
    final List<? extends SpanSamplingRule> spanSamplingRules;
    final List<? extends TraceSamplingRule> traceSamplingRules;

    Snapshot(Builder builder) {
      this.serviceMapping = builder.serviceMapping;
      this.taggedHeaders = builder.taggedHeaders;
      this.baggageMapping = builder.baggageMapping;
      this.spanSamplingRules = builder.spanSamplingRules;
      this.traceSamplingRules = builder.traceSamplingRules;
    }

    @Override
    public Map<String, String> getServiceMapping() {
      return serviceMapping;
    }

    @Override
    public Map<String, String> getTaggedHeaders() {
      return taggedHeaders;
    }

    @Override
    public Map<String, String> getBaggageMapping() {
      return baggageMapping;
    }

    @Override
    public List<? extends SpanSamplingRule> getSpanSamplingRules() {
      return this.spanSamplingRules;
    }

    @Override
    public List<? extends TraceSamplingRule> getTraceSamplingRules() {
      return traceSamplingRules;
    }
  }
}
