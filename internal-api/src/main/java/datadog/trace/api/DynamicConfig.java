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
  private Snapshot initialSnapshot;
  private volatile Snapshot currentSnapshot;

  private DynamicConfig() {}

  public static Builder create() {
    return new DynamicConfig().initial();
  }

  /** Captures a snapshot of the configuration at the start of a trace. */
  public TraceConfig captureTraceConfig() {
    return currentSnapshot;
  }

  /** Start building a new configuration based on its initial state. */
  public Builder initial() {
    return new Builder(initialSnapshot);
  }

  /** Start building a new configuration based on its current state. */
  public Builder current() {
    return new Builder(currentSnapshot);
  }

  /** Reset the configuration to its initial state. */
  public void resetTraceConfig() {
    currentSnapshot = initialSnapshot;
  }

  public final class Builder {

    Map<String, String> serviceMapping;
    Map<String, String> headerTags;
    Map<String, String> baggageMapping;

    Builder(Snapshot snapshot) {
      if (null == snapshot) {
        this.serviceMapping = Collections.emptyMap();
        this.headerTags = Collections.emptyMap();
        this.baggageMapping = Collections.emptyMap();
      } else {
        this.serviceMapping = snapshot.serviceMapping;
        this.headerTags = snapshot.headerTags;
        this.baggageMapping = snapshot.baggageMapping;
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
      Snapshot newSnapshot = new Snapshot(this, initialSnapshot);
      Snapshot oldSnapshot = currentSnapshot;
      if (null == oldSnapshot) {
        initialSnapshot = newSnapshot; // captured when constructing the dynamic config
        currentSnapshot = newSnapshot;
      } else {
        currentSnapshot = newSnapshot;
        Map<String, Object> update = new HashMap<>();
        update.put(SERVICE_MAPPING, newSnapshot.serviceMapping);
        update.put(HEADER_TAGS, newSnapshot.headerTags);
        update.put(BAGGAGE_MAPPING, newSnapshot.baggageMapping);
        ConfigCollector.get().putAll(update);
      }
      return DynamicConfig.this;
    }
  }

  /** Immutable snapshot of the configuration. */
  static final class Snapshot implements TraceConfig {

    final Map<String, String> serviceMapping;
    final Map<String, String> headerTags;
    final Map<String, String> baggageMapping;

    private final boolean overrideResponseTags;

    Snapshot(Builder builder, Snapshot initialSnapshot) {
      this.serviceMapping = builder.serviceMapping;
      this.headerTags = builder.headerTags;
      this.baggageMapping = builder.baggageMapping;

      // also apply headerTags to response headers if the initial reference has been overridden
      this.overrideResponseTags =
          null != initialSnapshot && headerTags != initialSnapshot.headerTags;
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
  }
}
