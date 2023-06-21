package datadog.trace.api;

import static datadog.trace.api.config.GeneralConfig.DATA_STREAMS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.RUNTIME_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACE_DEBUG;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TracerConfig.BAGGAGE_MAPPING;
import static datadog.trace.api.config.TracerConfig.HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.SERVICE_MAPPING;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableMap;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;

/** Manages dynamic configuration for a particular {@link Tracer} instance. */
public final class DynamicConfig<S extends DynamicConfig.Snapshot> {
  BiFunction<Builder, S, S> snapshotFactory;

  S initialSnapshot;
  volatile S currentSnapshot;

  private DynamicConfig(BiFunction<Builder, S, S> snapshotFactory) {
    this.snapshotFactory = snapshotFactory;
  }

  /** Dynamic configuration that uses the default snapshot type. */
  public static DynamicConfig<Snapshot>.Builder create() {
    return new DynamicConfig<>(Snapshot::new).initial();
  }

  /** Dynamic configuration that wants to add its own state to the snapshot. */
  public static <S extends DynamicConfig.Snapshot> DynamicConfig<S>.Builder create(
      BiFunction<DynamicConfig<S>.Builder, S, S> snapshotFactory) {
    return new DynamicConfig<S>(snapshotFactory).initial();
  }

  /** Captures a snapshot of the configuration at the start of a trace. */
  public S captureTraceConfig() {
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
    reportConfigChange(initialSnapshot);
  }

  public final class Builder {

    boolean debugEnabled;
    boolean runtimeMetricsEnabled;
    boolean logsInjectionEnabled;
    boolean dataStreamsEnabled;

    Map<String, String> serviceMapping;
    Map<String, String> headerTags;
    Map<String, String> baggageMapping;

    Double traceSampleRate;

    Builder(Snapshot snapshot) {
      if (null == snapshot) {
        this.serviceMapping = Collections.emptyMap();
        this.headerTags = Collections.emptyMap();
        this.baggageMapping = Collections.emptyMap();
      } else {

        this.debugEnabled = snapshot.debugEnabled;
        this.runtimeMetricsEnabled = snapshot.runtimeMetricsEnabled;
        this.logsInjectionEnabled = snapshot.logsInjectionEnabled;
        this.dataStreamsEnabled = snapshot.dataStreamsEnabled;

        this.serviceMapping = snapshot.serviceMapping;
        this.headerTags = snapshot.headerTags;
        this.baggageMapping = snapshot.baggageMapping;

        this.traceSampleRate = snapshot.traceSampleRate;
      }
    }

    public Builder setDebugEnabled(boolean debugEnabled) {
      this.debugEnabled = debugEnabled;
      return this;
    }

    public Builder setRuntimeMetricsEnabled(boolean runtimeMetricsEnabled) {
      this.runtimeMetricsEnabled = runtimeMetricsEnabled;
      return this;
    }

    public Builder setLogsInjectionEnabled(boolean logsInjectionEnabled) {
      this.logsInjectionEnabled = logsInjectionEnabled;
      return this;
    }

    public Builder setDataStreamsEnabled(boolean dataStreamsEnabled) {
      this.dataStreamsEnabled = dataStreamsEnabled;
      return this;
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

    public Builder setTraceSampleRate(Double traceSampleRate) {
      this.traceSampleRate = traceSampleRate;
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
          key = key.toLowerCase(Locale.ROOT);
        }
        String value = association.getValue().trim();
        if (lowerCaseValues) {
          value = value.toLowerCase(Locale.ROOT);
        }
        cleanedMapping.put(key, value);
      }
      return tryMakeImmutableMap(cleanedMapping);
    }

    /** Overwrites the current configuration with a new snapshot. */
    public DynamicConfig<S> apply() {
      S newSnapshot = snapshotFactory.apply(this, initialSnapshot);
      S oldSnapshot = currentSnapshot;
      if (null == oldSnapshot) {
        initialSnapshot = newSnapshot; // captured when constructing the dynamic config
        currentSnapshot = newSnapshot;
      } else {
        currentSnapshot = newSnapshot;
        reportConfigChange(newSnapshot);
      }
      return DynamicConfig.this;
    }
  }

  static void reportConfigChange(Snapshot newSnapshot) {
    Map<String, Object> update = new HashMap<>();

    update.put(TRACE_DEBUG, newSnapshot.debugEnabled);
    update.put(RUNTIME_METRICS_ENABLED, newSnapshot.runtimeMetricsEnabled);
    update.put(LOGS_INJECTION_ENABLED, newSnapshot.logsInjectionEnabled);
    update.put(DATA_STREAMS_ENABLED, newSnapshot.dataStreamsEnabled);

    update.put(SERVICE_MAPPING, newSnapshot.serviceMapping);
    update.put(HEADER_TAGS, newSnapshot.headerTags);
    update.put(BAGGAGE_MAPPING, newSnapshot.baggageMapping);

    maybePut(update, TRACE_SAMPLE_RATE, newSnapshot.traceSampleRate);

    ConfigCollector.get().putAll(update);
  }

  private static void maybePut(Map<String, Object> update, String key, Object value) {
    if (null != value) {
      update.put(key, value);
    }
  }

  /** Immutable snapshot of the configuration. */
  public static class Snapshot implements TraceConfig {

    final boolean debugEnabled;
    final boolean runtimeMetricsEnabled;
    final boolean logsInjectionEnabled;
    final boolean dataStreamsEnabled;

    final Map<String, String> serviceMapping;
    final Map<String, String> headerTags;
    final Map<String, String> baggageMapping;

    final Double traceSampleRate;

    private final boolean overrideResponseTags;

    protected Snapshot(DynamicConfig<?>.Builder builder, Snapshot initialSnapshot) {

      this.debugEnabled = builder.debugEnabled;
      this.runtimeMetricsEnabled = builder.runtimeMetricsEnabled;
      this.logsInjectionEnabled = builder.logsInjectionEnabled;
      this.dataStreamsEnabled = builder.dataStreamsEnabled;

      this.serviceMapping = builder.serviceMapping;
      this.headerTags = builder.headerTags;
      this.baggageMapping = builder.baggageMapping;

      this.traceSampleRate = builder.traceSampleRate;

      // also apply headerTags to response headers if the initial reference has been overridden
      this.overrideResponseTags =
          null != initialSnapshot && headerTags != initialSnapshot.headerTags;
    }

    @Override
    public boolean isDebugEnabled() {
      return debugEnabled;
    }

    @Override
    public boolean isRuntimeMetricsEnabled() {
      return runtimeMetricsEnabled;
    }

    @Override
    public boolean isLogsInjectionEnabled() {
      return logsInjectionEnabled;
    }

    @Override
    public boolean isDataStreamsEnabled() {
      return dataStreamsEnabled;
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

    @Override
    public Double getTraceSampleRate() {
      return traceSampleRate;
    }
  }
}
