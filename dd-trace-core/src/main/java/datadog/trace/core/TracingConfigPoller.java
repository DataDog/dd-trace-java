package datadog.trace.core;

import static datadog.remoteconfig.Capabilities.CAPABILITY_APM_CUSTOM_TAGS;
import static datadog.remoteconfig.Capabilities.CAPABILITY_APM_HTTP_HEADER_TAGS;
import static datadog.remoteconfig.Capabilities.CAPABILITY_APM_LOGS_INJECTION;
import static datadog.remoteconfig.Capabilities.CAPABILITY_APM_TRACING_DATA_STREAMS_ENABLED;
import static datadog.remoteconfig.Capabilities.CAPABILITY_APM_TRACING_SAMPLE_RATE;
import static datadog.remoteconfig.Capabilities.CAPABILITY_APM_TRACING_SAMPLE_RULES;
import static datadog.remoteconfig.Capabilities.CAPABILITY_APM_TRACING_TRACING_ENABLED;
import static datadog.trace.api.sampling.SamplingRule.normalizeGlob;

import com.squareup.moshi.FromJson;
import com.squareup.moshi.Json;
import com.squareup.moshi.JsonAdapter;
import com.squareup.moshi.JsonReader;
import com.squareup.moshi.Moshi;
import com.squareup.moshi.ToJson;
import datadog.communication.ddagent.SharedCommunicationObjects;
import datadog.remoteconfig.ConfigurationPoller;
import datadog.remoteconfig.PollingRateHinter;
import datadog.remoteconfig.Product;
import datadog.remoteconfig.state.ConfigKey;
import datadog.remoteconfig.state.ProductListener;
import datadog.trace.api.Config;
import datadog.trace.api.DynamicConfig;
import datadog.trace.api.sampling.SamplingRule;
import datadog.trace.logging.GlobalLogLevelSwitcher;
import datadog.trace.logging.LogLevel;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import okio.BufferedSource;
import okio.Okio;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final class TracingConfigPoller {
  static final Logger log = LoggerFactory.getLogger(TracingConfigPoller.class);

  private final DynamicConfig<?> dynamicConfig;

  private boolean startupLogsEnabled;

  private Runnable stopPolling;

  public TracingConfigPoller(DynamicConfig<?> dynamicConfig) {
    this.dynamicConfig = dynamicConfig;
  }

  public void start(Config config, SharedCommunicationObjects sco) {
    this.startupLogsEnabled = config.isStartupLogsEnabled();
    ConfigurationPoller configPoller = sco.configurationPoller(config);

    if (configPoller != null) {
      configPoller.addCapabilities(
          CAPABILITY_APM_TRACING_TRACING_ENABLED
              | CAPABILITY_APM_TRACING_SAMPLE_RATE
              | CAPABILITY_APM_LOGS_INJECTION
              | CAPABILITY_APM_HTTP_HEADER_TAGS
              | CAPABILITY_APM_CUSTOM_TAGS
              | CAPABILITY_APM_TRACING_DATA_STREAMS_ENABLED
              | CAPABILITY_APM_TRACING_SAMPLE_RULES);
    }
    stopPolling = new Updater().register(config, configPoller);
  }

  public void stop() {
    if (null != stopPolling) {
      stopPolling.run();
    }
  }

  final class Updater implements ProductListener {
    private final JsonAdapter<ConfigOverrides> CONFIG_OVERRIDES_ADAPTER;
    private final JsonAdapter<TracingSamplingRule> TRACE_SAMPLING_RULE;

    {
      Moshi MOSHI = new Moshi.Builder().add(new TracingSamplingRulesAdapter()).build();
      CONFIG_OVERRIDES_ADAPTER = MOSHI.adapter(ConfigOverrides.class);
      TRACE_SAMPLING_RULE = MOSHI.adapter(TracingSamplingRule.class);
    }

    private boolean receivedOverrides = false;

    public Runnable register(Config config, ConfigurationPoller poller) {
      if (null != poller) {
        poller.addListener(Product.APM_TRACING, this);
        return poller::stop;
      } else {
        return null;
      }
    }

    @Override
    public void accept(ConfigKey configKey, byte[] content, PollingRateHinter hinter)
        throws IOException {

      ConfigOverrides overrides =
          CONFIG_OVERRIDES_ADAPTER.fromJson(
              Okio.buffer(Okio.source(new ByteArrayInputStream(content))));

      if (null != overrides && null != overrides.libConfig) {
        ServiceTarget serviceTarget = overrides.serviceTarget;
        if (serviceTarget != null) {
          String targetService = serviceTarget.service;
          String thisService = Config.get().getServiceName();
          if (targetService != null && !targetService.equalsIgnoreCase(thisService)) {
            log.debug(
                "Skipping config for service {}. Current service is {}",
                targetService,
                thisService);
            throw new IllegalArgumentException("service mismatch");
          }
          String targetEnv = serviceTarget.env;
          String thisEnv = Config.get().getEnv();
          if (targetEnv != null && !targetEnv.equalsIgnoreCase(thisEnv)) {
            log.debug("Skipping config for env {}. Current env is {}", targetEnv, thisEnv);
            throw new IllegalArgumentException("env mismatch");
          }
        }
        receivedOverrides = true;
        applyConfigOverrides(checkConfig(overrides.libConfig));
        if (log.isDebugEnabled()) {
          log.debug(
              "Applied APM_TRACING overrides: {}", CONFIG_OVERRIDES_ADAPTER.toJson(overrides));
        }
      } else {
        log.debug("No APM_TRACING overrides");
      }
    }

    @Override
    public void remove(ConfigKey configKey, PollingRateHinter hinter) {}

    @Override
    public void commit(PollingRateHinter hinter) {
      if (!receivedOverrides) {
        removeConfigOverrides();
        log.debug("Removed APM_TRACING overrides");
      } else {
        receivedOverrides = false;
      }
    }

    private LibConfig checkConfig(LibConfig libConfig) {
      libConfig.traceSampleRate = checkSampleRate(libConfig.traceSampleRate);
      libConfig.tracingSamplingRules = checkSamplingRules(libConfig.tracingSamplingRules);
      return libConfig;
    }

    private Double checkSampleRate(Double sampleRate) {
      if (null != sampleRate) {
        if (sampleRate > 1.0) {
          log.debug("Unexpected sample rate {}, using 1.0", sampleRate);
          return 1.0;
        }
        if (sampleRate < 0.0) {
          log.debug("Unexpected sample rate {}, using 0.0", sampleRate);
          return 0.0;
        }
      }
      return sampleRate;
    }

    private TracingSamplingRules checkSamplingRules(TracingSamplingRules rules) {
      if (null == rules || null == rules.data) {
        return null;
      }
      for (Iterator<TracingSamplingRule> itr = rules.data.iterator(); itr.hasNext(); ) {
        TracingSamplingRule rule = itr.next();
        // check for required fields
        if ((null == rule.service
                && null == rule.name
                && null == rule.resource
                && null == rule.tags)
            || null == rule.sampleRate) {
          log.debug(
              "Invalid sampling rule from remote-config, rule will be removed: {}",
              TRACE_SAMPLING_RULE.toJson(rule));
          itr.remove();
        }
        rule.service = normalizeGlob(rule.service);
        rule.name = normalizeGlob(rule.name);
        rule.resource = normalizeGlob(rule.resource);
        rule.sampleRate = checkSampleRate(rule.sampleRate);
      }
      return rules;
    }
  }

  void applyConfigOverrides(LibConfig libConfig) {
    DynamicConfig<?>.Builder builder = dynamicConfig.initial();

    if (libConfig.debugEnabled != null) {
      if (Boolean.TRUE.equals(libConfig.debugEnabled)) {
        GlobalLogLevelSwitcher.get().switchLevel(LogLevel.DEBUG);
      } else {
        // Disable debugEnabled when it was set to true at startup
        // The default log level when debugEnabled=false depends on the STARTUP_LOGS_ENABLED flag
        // See datadog.trace.bootstrap.Agent.configureLogger()
        if (startupLogsEnabled) {
          GlobalLogLevelSwitcher.get().switchLevel(LogLevel.INFO);
        } else {
          GlobalLogLevelSwitcher.get().switchLevel(LogLevel.WARN);
        }
      }
    } else {
      GlobalLogLevelSwitcher.get().restore();
    }

    maybeOverride(builder::setTracingEnabled, libConfig.tracingEnabled);
    maybeOverride(builder::setRuntimeMetricsEnabled, libConfig.runtimeMetricsEnabled);
    maybeOverride(builder::setLogsInjectionEnabled, libConfig.logsInjectionEnabled);
    maybeOverride(builder::setDataStreamsEnabled, libConfig.dataStreamsEnabled);

    maybeOverride(builder::setServiceMapping, libConfig.serviceMapping);
    maybeOverride(builder::setHeaderTags, libConfig.headerTags);

    if (null != libConfig.tracingSamplingRules) {
      builder.setTraceSamplingRules(
          libConfig.tracingSamplingRules.data, libConfig.tracingSamplingRules.json);
    }
    maybeOverride(builder::setTraceSampleRate, libConfig.traceSampleRate);

    maybeOverride(builder::setTracingTags, parseTagListToMap(libConfig.tracingTags));

    builder.apply();
  }

  void removeConfigOverrides() {
    dynamicConfig.resetTraceConfig();
    GlobalLogLevelSwitcher.get().restore();
  }

  private <T> void maybeOverride(Consumer<T> setter, T override) {
    if (null != override) {
      setter.accept(override);
    }
  }

  private Map<String, String> parseTagListToMap(List<String> input) {
    if (null == input) {
      return null;
    }

    Map<String, String> resultMap = new HashMap<>(input.size());
    for (String s : input) {
      int colonIndex = s.indexOf(":");
      if (colonIndex > -1
          && colonIndex < s.length() - 1) { // ensure there's a colon that's not at the start or end
        String key = s.substring(0, colonIndex);
        String value = s.substring(colonIndex + 1);
        if (!key.isEmpty() && !value.isEmpty()) {
          resultMap.put(key, value);
        }
      }
    }

    return resultMap;
  }

  static final class ConfigOverrides {
    @Json(name = "service_target")
    public ServiceTarget serviceTarget;

    @Json(name = "lib_config")
    public LibConfig libConfig;
  }

  static final class ServiceTarget {
    @Json(name = "service")
    public String service;

    @Json(name = "env")
    public String env;
  }

  static final class LibConfig {
    @Json(name = "tracing_enabled")
    public Boolean tracingEnabled;

    @Json(name = "tracing_debug")
    public Boolean debugEnabled;

    @Json(name = "runtime_metrics_enabled")
    public Boolean runtimeMetricsEnabled;

    @Json(name = "log_injection_enabled")
    public Boolean logsInjectionEnabled;

    @Json(name = "data_streams_enabled")
    public Boolean dataStreamsEnabled;

    @Json(name = "tracing_service_mapping")
    public List<ServiceMappingEntry> serviceMapping;

    @Json(name = "tracing_header_tags")
    public List<HeaderTagEntry> headerTags;

    @Json(name = "tracing_sampling_rate")
    public Double traceSampleRate;

    @Json(name = "tracing_tags")
    public List<String> tracingTags;

    @Json(name = "tracing_sampling_rules")
    public TracingSamplingRules tracingSamplingRules;
  }

  /** Holds the raw JSON string and the parsed rule data. */
  static final class TracingSamplingRules {
    public final String json;

    public final List<TracingSamplingRule> data;

    TracingSamplingRules(String json, List<TracingSamplingRule> data) {
      this.json = json;
      this.data = data;
    }
  }

  /** Extracts the raw JSON first, so it can be saved, then parses it into rules. */
  static final class TracingSamplingRulesAdapter {
    @FromJson
    TracingSamplingRules fromJson(JsonReader reader, JsonAdapter<List<TracingSamplingRule>> parser)
        throws IOException {
      if (reader.peek() == JsonReader.Token.NULL) {
        return reader.nextNull();
      }
      try (BufferedSource source = reader.nextSource()) {
        String json = source.readUtf8();
        return new TracingSamplingRules(json, parser.fromJson(json));
      }
    }

    @ToJson
    String toJson(TracingSamplingRules rules) {
      return rules.json;
    }
  }

  static final class ServiceMappingEntry implements Map.Entry<String, String> {
    @Json(name = "from_key")
    public String fromKey;

    @Json(name = "to_name")
    public String toName;

    @Override
    public String getKey() {
      return fromKey;
    }

    @Override
    public String getValue() {
      return toName;
    }

    @Override
    public String setValue(String value) {
      throw new UnsupportedOperationException();
    }
  }

  static final class HeaderTagEntry implements Map.Entry<String, String> {
    @Json(name = "header")
    public String header;

    @Json(name = "tag_name")
    public String tagName;

    @Override
    public String getKey() {
      return header;
    }

    @Override
    public String getValue() {
      return tagName;
    }

    @Override
    public String setValue(String value) {
      throw new UnsupportedOperationException();
    }
  }

  static final class TracingSamplingRule implements SamplingRule.TraceSamplingRule {
    @Json(name = "service")
    public String service;

    @Json(name = "name")
    public String name;

    @Json(name = "resource")
    public String resource;

    @Json(name = "tags")
    public List<SamplingRuleTagEntry> tags;

    @Json(name = "sample_rate")
    public Double sampleRate;

    @Json(name = "provenance")
    public String provenance;

    private transient Map<String, String> tagMap;

    @Override
    public String getService() {
      return service;
    }

    @Override
    public String getName() {
      return name;
    }

    @Override
    public String getResource() {
      return resource;
    }

    @Override
    public Map<String, String> getTags() {
      if (null == tagMap) {
        tagMap =
            null == tags
                ? Collections.emptyMap()
                : tags.stream()
                    .collect(
                        Collectors.toMap(
                            SamplingRuleTagEntry::getKey, e -> normalizeGlob(e.getValue())));
      }
      return tagMap;
    }

    @Override
    public double getSampleRate() {
      return sampleRate;
    }

    @Override
    public Provenance getProvenance() {
      if ("dynamic".equals(provenance)) {
        return Provenance.DYNAMIC;
      } else {
        return Provenance.CUSTOMER;
      }
    }
  }

  static final class SamplingRuleTagEntry implements Map.Entry<String, String> {
    @Json(name = "key")
    public String key;

    @Json(name = "value_glob")
    public String value;

    @Override
    public String getKey() {
      return key;
    }

    @Override
    public String getValue() {
      return value;
    }

    @Override
    public String setValue(String value) {
      throw new UnsupportedOperationException();
    }
  }
}
