package datadog.trace.api;

import static datadog.trace.api.config.GeneralConfig.DATA_STREAMS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.RUNTIME_METRICS_ENABLED;
import static datadog.trace.api.config.GeneralConfig.TRACE_DEBUG;
import static datadog.trace.api.config.TraceInstrumentationConfig.LOGS_INJECTION_ENABLED;
import static datadog.trace.api.config.TracerConfig.BAGGAGE_MAPPING;
import static datadog.trace.api.config.TracerConfig.REQUEST_HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.RESPONSE_HEADER_TAGS;
import static datadog.trace.api.config.TracerConfig.SERVICE_MAPPING;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLE_RATE;
import static datadog.trace.api.config.TracerConfig.TRACE_SAMPLING_RULES;
import static datadog.trace.util.CollectionUtils.tryMakeImmutableMap;
import static datadog.trace.util.ConfigStrings.normalizedHeaderTag;
import static datadog.trace.util.ConfigStrings.trim;

import datadog.trace.api.sampling.SamplingRule.SpanSamplingRule;
import datadog.trace.api.sampling.SamplingRule.TraceSamplingRule;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Config that can be dynamically updated via remote-config
 *
 * <p>Only a small subset of config is currently supported
 *
 * <p>Not every config should be dynamic because there are performance implications
 *
 * @see InstrumenterConfig for pre-instrumentation configurations
 * @see Config for other configurations
 */
public final class DynamicConfig<S extends DynamicConfig.Snapshot> {
  static final Logger rootLogger = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);

  static final Function<Map.Entry<String, String>, String> KEY = DynamicConfig::key;
  static final Function<Map.Entry<String, String>, String> VALUE = DynamicConfig::value;
  static final Function<Map.Entry<String, String>, String> LOWER_KEY = DynamicConfig::lowerKey;
  static final Function<Map.Entry<String, String>, String> REQUEST_TAG = DynamicConfig::requestTag;
  static final Function<Map.Entry<String, String>, String> RESPONSE_TAG =
      DynamicConfig::responseTag;

  BiFunction<Builder, S, S> snapshotFactory;

  S initialSnapshot;
  volatile S currentSnapshot;

  private DynamicConfig(BiFunction<Builder, S, S> snapshotFactory) {
    this.snapshotFactory = snapshotFactory;
  }

  /** Dynamic configuration that uses the default snapshot type. */
  public static DynamicConfig<Snapshot>.Builder create() {
    return new DynamicConfig<>(Snapshot::new).new Builder();
  }

  /** Dynamic configuration that wants to add its own state to the snapshot. */
  public static <S extends DynamicConfig.Snapshot> DynamicConfig<S>.Builder create(
      BiFunction<DynamicConfig<S>.Builder, S, S> snapshotFactory) {
    return new DynamicConfig<S>(snapshotFactory).new Builder();
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

  @Override
  public String toString() {
    return currentSnapshot.toString();
  }

  public final class Builder {
    boolean tracingEnabled;
    boolean runtimeMetricsEnabled;
    boolean logsInjectionEnabled;
    boolean dataStreamsEnabled;

    Map<String, String> serviceMapping;
    Map<String, String> requestHeaderTags;
    Map<String, String> responseHeaderTags;
    Map<String, String> tracingTags;
    Map<String, String> baggageMapping;

    List<? extends SpanSamplingRule> spanSamplingRules;
    List<? extends TraceSamplingRule> traceSamplingRules;
    String traceSamplingRulesJson;
    Double traceSampleRate;

    String preferredServiceName;

    Builder() {}

    Builder(Snapshot snapshot) {

      this.tracingEnabled = snapshot.tracingEnabled;
      this.runtimeMetricsEnabled = snapshot.runtimeMetricsEnabled;
      this.logsInjectionEnabled = snapshot.logsInjectionEnabled;
      this.dataStreamsEnabled = snapshot.dataStreamsEnabled;

      this.serviceMapping = snapshot.serviceMapping;
      this.requestHeaderTags = snapshot.requestHeaderTags;
      this.responseHeaderTags = snapshot.responseHeaderTags;
      this.baggageMapping = snapshot.baggageMapping;

      this.spanSamplingRules = snapshot.spanSamplingRules;
      this.traceSamplingRules = snapshot.traceSamplingRules;
      this.traceSamplingRulesJson = snapshot.traceSamplingRulesJson;
      this.traceSampleRate = snapshot.traceSampleRate;

      this.tracingTags = snapshot.tracingTags;

      this.preferredServiceName = snapshot.preferredServiceName;
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

    @SuppressWarnings("deprecation")
    public Builder setHeaderTags(Map<String, String> headerTags) {
      if (Config.get().getRequestHeaderTags().equals(headerTags)
          && !Config.get().getResponseHeaderTags().equals(headerTags)) {
        // using static config; don't override separate static config for response header tags
        this.requestHeaderTags = Config.get().getRequestHeaderTags();
        this.responseHeaderTags = Config.get().getResponseHeaderTags();
        return this;
      } else {
        return setHeaderTags(headerTags.entrySet());
      }
    }

    public Builder setBaggageMapping(Map<String, String> baggageMapping) {
      return setBaggageMapping(baggageMapping.entrySet());
    }

    public Builder setServiceMapping(
        Collection<? extends Map.Entry<String, String>> serviceMapping) {
      this.serviceMapping = cleanMapping(serviceMapping, KEY, VALUE);
      return this;
    }

    public Builder setHeaderTags(Collection<? extends Map.Entry<String, String>> headerTags) {
      this.requestHeaderTags = cleanMapping(headerTags, LOWER_KEY, REQUEST_TAG);
      this.responseHeaderTags = cleanMapping(headerTags, LOWER_KEY, RESPONSE_TAG);
      return this;
    }

    public Builder setBaggageMapping(
        Collection<? extends Map.Entry<String, String>> baggageMapping) {
      this.baggageMapping = cleanMapping(baggageMapping, LOWER_KEY, VALUE);
      return this;
    }

    public Builder setTraceSampleRate(Double traceSampleRate) {
      this.traceSampleRate = traceSampleRate;
      return this;
    }

    public Builder setSpanSamplingRules(List<? extends SpanSamplingRule> spanSamplingRules) {
      this.spanSamplingRules = spanSamplingRules;
      return this;
    }

    public Builder setTraceSamplingRules(
        List<? extends TraceSamplingRule> traceSamplingRules, String traceSamplingRulesJson) {
      this.traceSamplingRules = traceSamplingRules;
      this.traceSamplingRulesJson = traceSamplingRulesJson;
      return this;
    }

    public Builder setTracingTags(Map<String, String> tracingTags) {
      this.tracingTags = tracingTags;
      return this;
    }

    public Builder setTracingEnabled(boolean tracingEnabled) {
      this.tracingEnabled = tracingEnabled;
      return this;
    }

    public Builder setPreferredServiceName(String preferredServiceName) {
      this.preferredServiceName = preferredServiceName;
      return this;
    }

    /** Overwrites the current configuration with a new snapshot. */
    public DynamicConfig<S> apply() {
      S oldSnapshot = currentSnapshot;
      S newSnapshot = snapshotFactory.apply(this, oldSnapshot);
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

  static Map<String, String> cleanMapping(
      Collection<? extends Map.Entry<String, String>> mapping,
      Function<Map.Entry<String, String>, String> keyMapper,
      Function<Map.Entry<String, String>, String> valueMapper) {
    final Map<String, String> cleanedMapping = new HashMap<>(mapping.size() * 4 / 3);
    for (Map.Entry<String, String> association : mapping) {
      cleanedMapping.put(keyMapper.apply(association), valueMapper.apply(association));
    }
    return tryMakeImmutableMap(cleanedMapping);
  }

  static String key(Map.Entry<String, String> association) {
    return trim(association.getKey());
  }

  static String value(Map.Entry<String, String> association) {
    return trim(association.getValue());
  }

  static String lowerKey(Map.Entry<String, String> association) {
    return key(association).toLowerCase(Locale.ROOT);
  }

  static String requestTag(Map.Entry<String, String> association) {
    String requestTag = value(association);
    if (requestTag.isEmpty()) {
      // normalization is only applied when generating default tag names; see ConfigConverter
      requestTag = "http.request.headers." + normalizedHeaderTag(association.getKey());
    }
    return requestTag;
  }

  static String responseTag(Map.Entry<String, String> association) {
    String responseTag = value(association);
    if (responseTag.isEmpty()) {
      // normalization is only applied when generating default tag names; see ConfigConverter
      responseTag = "http.response.headers." + normalizedHeaderTag(association.getKey());
    }
    return responseTag;
  }

  static void reportConfigChange(Snapshot newSnapshot) {
    Map<String, Object> update = new HashMap<>();

    update.put(TRACE_DEBUG, rootLogger.isDebugEnabled());
    update.put(RUNTIME_METRICS_ENABLED, newSnapshot.runtimeMetricsEnabled);
    update.put(LOGS_INJECTION_ENABLED, newSnapshot.logsInjectionEnabled);
    update.put(DATA_STREAMS_ENABLED, newSnapshot.dataStreamsEnabled);

    update.put(SERVICE_MAPPING, newSnapshot.serviceMapping);
    update.put(REQUEST_HEADER_TAGS, newSnapshot.requestHeaderTags);
    update.put(RESPONSE_HEADER_TAGS, newSnapshot.responseHeaderTags);
    update.put(BAGGAGE_MAPPING, newSnapshot.baggageMapping);

    update.put(TRACE_SAMPLING_RULES, newSnapshot.traceSamplingRulesJson);
    maybePut(update, TRACE_SAMPLE_RATE, newSnapshot.traceSampleRate);

    ConfigCollector.get().putRemote(update);
  }

  @SuppressWarnings("SameParameterValue")
  private static void maybePut(Map<String, Object> update, String key, Object value) {
    if (null != value) {
      update.put(key, value);
    }
  }

  /** Immutable snapshot of the configuration. */
  public static class Snapshot implements TraceConfig {
    final boolean tracingEnabled;
    final boolean runtimeMetricsEnabled;
    final boolean logsInjectionEnabled;
    final boolean dataStreamsEnabled;

    final Map<String, String> serviceMapping;
    final Map<String, String> requestHeaderTags;
    final Map<String, String> responseHeaderTags;
    final Map<String, String> baggageMapping;

    final List<? extends SpanSamplingRule> spanSamplingRules;
    final List<? extends TraceSamplingRule> traceSamplingRules;
    final String traceSamplingRulesJson;

    final Double traceSampleRate;
    final Map<String, String> tracingTags;

    final String preferredServiceName;

    protected Snapshot(DynamicConfig<?>.Builder builder, Snapshot oldSnapshot) {

      this.tracingEnabled = builder.tracingEnabled;
      this.runtimeMetricsEnabled = builder.runtimeMetricsEnabled;
      this.logsInjectionEnabled = builder.logsInjectionEnabled;
      this.dataStreamsEnabled = builder.dataStreamsEnabled;

      this.serviceMapping = nullToEmpty(builder.serviceMapping);
      this.requestHeaderTags = nullToEmpty(builder.requestHeaderTags);
      this.responseHeaderTags = nullToEmpty(builder.responseHeaderTags);
      this.baggageMapping = nullToEmpty(builder.baggageMapping);

      this.spanSamplingRules = nullToEmpty(builder.spanSamplingRules);
      this.traceSamplingRules = nullToEmpty(builder.traceSamplingRules);
      this.traceSamplingRulesJson = builder.traceSamplingRulesJson;
      this.traceSampleRate = builder.traceSampleRate;

      this.tracingTags = nullToEmpty(builder.tracingTags);

      this.preferredServiceName = builder.preferredServiceName;
    }

    private static <K, V> Map<K, V> nullToEmpty(Map<K, V> mapping) {
      return null != mapping ? mapping : Collections.emptyMap();
    }

    private static <V> List<V> nullToEmpty(List<V> list) {
      return null != list ? list : Collections.emptyList();
    }

    @Override
    public boolean isTraceEnabled() {
      return tracingEnabled;
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
    public Map<String, String> getRequestHeaderTags() {
      return requestHeaderTags;
    }

    @Override
    public Map<String, String> getResponseHeaderTags() {
      return responseHeaderTags;
    }

    @Override
    public Map<String, String> getBaggageMapping() {
      return baggageMapping;
    }

    @Override
    public Double getTraceSampleRate() {
      return traceSampleRate;
    }

    @Override
    public String getPreferredServiceName() {
      return preferredServiceName;
    }

    @Override
    public List<? extends SpanSamplingRule> getSpanSamplingRules() {
      return spanSamplingRules;
    }

    @Override
    public List<? extends TraceSamplingRule> getTraceSamplingRules() {
      return traceSamplingRules;
    }

    @Override
    public Map<String, String> getTracingTags() {
      return tracingTags;
    }

    @Override
    public String toString() {
      return "DynamicConfig{"
          + "debugEnabled="
          + rootLogger.isDebugEnabled()
          + ", runtimeMetricsEnabled="
          + runtimeMetricsEnabled
          + ", logsInjectionEnabled="
          + logsInjectionEnabled
          + ", dataStreamsEnabled="
          + dataStreamsEnabled
          + ", serviceMapping="
          + serviceMapping
          + ", requestHeaderTags="
          + requestHeaderTags
          + ", responseHeaderTags="
          + responseHeaderTags
          + ", baggageMapping="
          + baggageMapping
          + ", spanSamplingRules="
          + spanSamplingRules
          + ", traceSamplingRules="
          + traceSamplingRulesJson
          + ", traceSampleRate="
          + traceSampleRate
          + ", tracingTags="
          + tracingTags
          + ", preferredServiceName="
          + preferredServiceName
          + '}';
    }
  }
}
