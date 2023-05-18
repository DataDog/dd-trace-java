package datadog.trace.api;

import static datadog.trace.util.CollectionUtils.tryMakeImmutableMap;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Manages dynamic configuration for a particular {@link Tracer} instance. */
public final class DynamicConfig {
  private volatile State currentState;

  private DynamicConfig() {}

  public static Builder create() {
    return new DynamicConfig().prepare();
  }

  /** Captures a snapshot of the configuration at the start of a trace. */
  public TraceConfig captureTraceConfig() {
    return currentState;
  }

  public Builder prepare() {
    return new Builder(currentState);
  }

  public final class Builder {

    Map<String, String> serviceMapping;
    Map<String, String> taggedHeaders;
    Map<String, String> baggageMapping;

    Builder(State state) {
      if (null == state) {
        this.serviceMapping = Collections.emptyMap();
        this.taggedHeaders = Collections.emptyMap();
        this.baggageMapping = Collections.emptyMap();
      } else {
        this.serviceMapping = state.serviceMapping;
        this.taggedHeaders = state.taggedHeaders;
        this.baggageMapping = state.baggageMapping;
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

    /** Overwrites the current configuration with a new snapshot. */
    public DynamicConfig apply() {
      DynamicConfig.this.currentState = new State(this);
      return DynamicConfig.this;
    }
  }

  /** Immutable snapshot of the configuration. */
  static final class State implements TraceConfig {

    final Map<String, String> serviceMapping;
    final Map<String, String> taggedHeaders;
    final Map<String, String> baggageMapping;

    State(Builder builder) {
      this.serviceMapping = builder.serviceMapping;
      this.taggedHeaders = builder.taggedHeaders;
      this.baggageMapping = builder.baggageMapping;
    }

    public Map<String, String> getServiceMapping() {
      return serviceMapping;
    }

    public Map<String, String> getTaggedHeaders() {
      return taggedHeaders;
    }

    public Map<String, String> getBaggageMapping() {
      return baggageMapping;
    }
  }
}
