package datadog.trace.api;

import static datadog.trace.api.config.TracerConfig.BAGGAGE_MAPPING;
import static datadog.trace.api.config.TracerConfig.HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.SERVICE_MAPPING;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableMap;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Manages dynamic configuration for a particular {@link Tracer} instance. */
public final class DynamicConfig {
  private State initialState;
  private volatile State currentState;

  private DynamicConfig() {}

  public static Builder create() {
    return new DynamicConfig().initial();
  }

  /** Captures a snapshot of the configuration at the start of a trace. */
  public TraceConfig captureTraceConfig() {
    return currentState;
  }

  /** Start building a new configuration based on its initial state. */
  public Builder initial() {
    return new Builder(initialState);
  }

  /** Start building a new configuration based on its current state. */
  public Builder current() {
    return new Builder(currentState);
  }

  /** Reset the configuration to its initial state. */
  public void resetTraceConfig() {
    currentState = initialState;
  }

  public final class Builder {

    Map<String, String> serviceMapping;
    Map<String, String> headerTags;
    Map<String, String> baggageMapping;
    boolean logsInjectionEnabled;

    Builder(State state) {
      if (null == state) {
        this.serviceMapping = Collections.emptyMap();
        this.headerTags = Collections.emptyMap();
        this.baggageMapping = Collections.emptyMap();
        this.logsInjectionEnabled = ConfigDefaults.DEFAULT_LOGS_INJECTION_ENABLED;
      } else {
        this.serviceMapping = state.serviceMapping;
        this.headerTags = state.headerTags;
        this.baggageMapping = state.baggageMapping;
        this.logsInjectionEnabled = state.logsInjectionEnabled;
      }
    }

    public Builder setServiceMapping(Map<String, String> serviceMapping) {
      return setServiceMapping(serviceMapping.entrySet());
    }

    public Builder setHeaderTags(Map<String, String> headerTags) {
      return setHeaderTags(headerTags.entrySet());
    }

    public Builder setBaggageMapping(Map<String, String> baggageMapping) {
      return setBaggageMapping(baggageMapping.entrySet());
    }

    public Builder setServiceMapping(
        Collection<? extends Map.Entry<String, String>> serviceMapping) {
      this.serviceMapping = cleanMapping(serviceMapping, false, false);
      return this;
    }

    public Builder setHeaderTags(Collection<? extends Map.Entry<String, String>> headerTags) {
      this.headerTags = cleanMapping(headerTags, true, true);
      return this;
    }

    public Builder setBaggageMapping(
        Collection<? extends Map.Entry<String, String>> baggageMapping) {
      this.baggageMapping = cleanMapping(baggageMapping, true, false);
      return this;
    }

    public Builder setLogsInjectionEnabled(boolean logsInjectionEnabled) {
      this.logsInjectionEnabled = logsInjectionEnabled;
      return this;
    }

    private Map<String, String> cleanMapping(
        Collection<? extends Map.Entry<String, String>> mapping,
        boolean lowerCaseKeys,
        boolean lowerCaseValues) {
      final Map<String, String> cleanedMapping = new HashMap<>(mapping.size() * 4 / 3);
      for (Map.Entry<String, String> association : mapping) {
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
      State newState = new State(this, initialState);
      State oldState = currentState;
      if (null == oldState) {
        initialState = newState; // captured when constructing the dynamic config
        currentState = newState;
      } else {
        currentState = newState;
        Map<String, Object> update = new HashMap<>();
        update.put(SERVICE_MAPPING, newState.serviceMapping);
        update.put(HEADER_TAGS, newState.headerTags);
        update.put(BAGGAGE_MAPPING, newState.baggageMapping);
        ConfigCollector.get().putAll(update);
      }
      return DynamicConfig.this;
    }
  }

  /** Immutable snapshot of the configuration. */
  static final class State implements TraceConfig {

    final Map<String, String> serviceMapping;
    final Map<String, String> headerTags;
    final Map<String, String> baggageMapping;
    final boolean logsInjectionEnabled;

    private final boolean overrideResponseTags;

    State(Builder builder, State initialState) {
      this.serviceMapping = builder.serviceMapping;
      this.headerTags = builder.headerTags;
      this.baggageMapping = builder.baggageMapping;
      this.logsInjectionEnabled = builder.logsInjectionEnabled;

      // also apply headerTags to response headers if the initial reference has been overridden
      this.overrideResponseTags = null != initialState && headerTags != initialState.headerTags;
    }

    @Override
    public Map<String, String> getServiceMapping() {
      return serviceMapping;
    }

    @Override
    public Map<String, String> getHeaderTags() {
      return headerTags;
    }

    @Override
    public Map<String, String> getResponseHeaderTags() {
      return overrideResponseTags ? headerTags : Config.get().getResponseHeaderTags();
    }

    @Override
    public Map<String, String> getBaggageMapping() {
      return baggageMapping;
    }

    public boolean isLogsInjectionEnabled() {
      return logsInjectionEnabled;
    }
  }
}
